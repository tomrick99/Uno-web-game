package com.uno.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class PlayerConnectionService {

    static final long DEFAULT_OFFLINE_TIMEOUT_MILLIS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(PlayerConnectionService.class);

    private final GameService gameService;
    private final ScheduledExecutorService scheduler;
    private final long offlineTimeoutMillis;
    private final Object monitor = new Object();
    private final Map<String, Set<String>> activeSessions = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingTimeouts = new HashMap<>();

    @Autowired
    public PlayerConnectionService(
            GameService gameService,
            @Value("${uno.game.offline-timeout-ms:30000}") long offlineTimeoutMillis) {
        this(
                gameService,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "uno-player-offline-timeout");
                    thread.setDaemon(true);
                    return thread;
                }),
                offlineTimeoutMillis
        );
    }

    PlayerConnectionService(GameService gameService,
                            ScheduledExecutorService scheduler,
                            long offlineTimeoutMillis) {
        this.gameService = gameService;
        this.scheduler = scheduler;
        this.offlineTimeoutMillis = Math.max(1L, offlineTimeoutMillis);
    }

    @EventListener
    public void handleSessionConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser() != null ? accessor.getUser() : event.getUser();
        playerConnected(principal != null ? principal.getName() : null, accessor.getSessionId());
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Principal principal = event.getUser();
        playerDisconnected(principal != null ? principal.getName() : null, event.getSessionId());
    }

    void playerConnected(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }

        synchronized (monitor) {
            activeSessions.computeIfAbsent(username, ignored -> new HashSet<>()).add(sessionId);
            ScheduledFuture<?> pendingTimeout = pendingTimeouts.remove(username);
            if (pendingTimeout != null) {
                pendingTimeout.cancel(false);
                log.info("[WS] offline timeout cancelled user={}", username);
            }
        }
    }

    void playerDisconnected(String username, String sessionId) {
        if (username == null || username.isBlank()) {
            return;
        }

        synchronized (monitor) {
            Set<String> sessions = activeSessions.get(username);
            if (sessions == null) {
                return;
            }
            boolean removed;
            if (sessionId == null || sessionId.isBlank()) {
                removed = !sessions.isEmpty();
                sessions.clear();
            } else {
                removed = sessions.remove(sessionId);
            }
            if (!removed || !sessions.isEmpty()) {
                return;
            }
            activeSessions.remove(username);

            ScheduledFuture<?> previousTimeout = pendingTimeouts.remove(username);
            if (previousTimeout != null) {
                previousTimeout.cancel(false);
            }
            ScheduledFuture<?> timeout = scheduler.schedule(
                    () -> expireIfStillOffline(username),
                    offlineTimeoutMillis,
                    TimeUnit.MILLISECONDS
            );
            pendingTimeouts.put(username, timeout);
            log.info("[WS] offline timeout scheduled user={} timeoutMs={}", username, offlineTimeoutMillis);
        }
    }

    private void expireIfStillOffline(String username) {
        synchronized (monitor) {
            Set<String> sessions = activeSessions.get(username);
            if (sessions != null && !sessions.isEmpty()) {
                pendingTimeouts.remove(username);
                return;
            }
            pendingTimeouts.remove(username);
        }

        try {
            log.info("[WS] offline timeout expired user={}", username);
            gameService.handlePlayerOfflineTimeout(username);
        } catch (RuntimeException ex) {
            log.error("[WS] offline timeout processing failed user={}", username, ex);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
