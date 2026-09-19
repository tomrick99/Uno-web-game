package com.uno.websocket;

import com.uno.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PlayerConnectionTracker {

    static final Duration OFFLINE_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(PlayerConnectionTracker.class);

    private final GameService gameService;
    private final TaskScheduler taskScheduler;
    private final ConcurrentHashMap<String, Long> sessionUsers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Set<String>> userSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> userLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> offlineTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();
    private final AtomicLong tokenSequence = new AtomicLong();

    public PlayerConnectionTracker(GameService gameService,
                                   @Qualifier("playerOfflineTaskScheduler") TaskScheduler taskScheduler) {
        this.gameService = gameService;
        this.taskScheduler = taskScheduler;
    }

    @EventListener
    public void onSessionConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Long userId = extractUserId(accessor);
        if (accessor.getSessionId() != null && userId != null) {
            playerConnected(accessor.getSessionId(), userId);
        }
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null) {
            playerDisconnected(event.getSessionId());
        }
    }

    void playerConnected(String sessionId, Long userId) {
        synchronized (userLock(userId)) {
            sessionUsers.put(sessionId, userId);
            userSessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
            offlineTokens.remove(userId);
            ScheduledFuture<?> pending = pendingTimeouts.remove(userId);
            if (pending != null) {
                pending.cancel(false);
            }
            log.info("[WS] player online userId={} activeSessions={}", userId, userSessions.get(userId).size());
        }
    }

    void playerDisconnected(String sessionId) {
        Long userId = sessionUsers.remove(sessionId);
        if (userId == null) {
            return;
        }

        synchronized (userLock(userId)) {
            Set<String> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (!sessions.isEmpty()) {
                    log.info("[WS] session disconnected userId={} activeSessions={}", userId, sessions.size());
                    return;
                }
                userSessions.remove(userId, sessions);
            }

            long token = tokenSequence.incrementAndGet();
            offlineTokens.put(userId, token);
            Instant deadline = Instant.now().plus(OFFLINE_TIMEOUT);
            ScheduledFuture<?> timeout = taskScheduler.schedule(
                    () -> expireIfStillOffline(userId, token),
                    deadline);
            if (timeout != null) {
                ScheduledFuture<?> previous = pendingTimeouts.put(userId, timeout);
                if (previous != null) {
                    previous.cancel(false);
                }
            }
            log.info("[WS] player offline userId={} timeoutSeconds={}", userId, OFFLINE_TIMEOUT.toSeconds());
        }
    }

    private void expireIfStillOffline(Long userId, long token) {
        synchronized (userLock(userId)) {
            if (!offlineTokens.remove(userId, token)) {
                return;
            }
            pendingTimeouts.remove(userId);
            Set<String> sessions = userSessions.get(userId);
            if (sessions != null && !sessions.isEmpty()) {
                return;
            }
            try {
                gameService.handleOfflineTimeout(userId);
            } catch (RuntimeException ex) {
                log.error("[WS] offline timeout failed userId={}", userId, ex);
            }
        }
    }

    private Object userLock(Long userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private Long extractUserId(StompHeaderAccessor accessor) {
        if (accessor.getSessionAttributes() == null) {
            return null;
        }
        Object value = accessor.getSessionAttributes().get("userId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
