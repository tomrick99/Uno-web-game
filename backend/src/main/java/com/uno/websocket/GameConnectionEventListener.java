package com.uno.websocket;

import com.uno.auth.ActiveLoginService;
import com.uno.auth.LoginSessionReplacedEvent;
import com.uno.service.GameService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GameConnectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(GameConnectionEventListener.class);
    // Matches the client's existing reconnect/fallback threshold so normal page and socket replacement
    // can establish the successor session before C2 treats the player as truly disconnected.
    static final long CONNECTION_REPLACEMENT_WINDOW_MS = 5_000L;

    private final GameService gameService;
    private final TaskScheduler taskScheduler;
    private final GameWebSocketService webSocketService;
    private final ActiveWebSocketSessionRegistry activeSessionRegistry;
    private final Map<String, String> usernameBySession = new ConcurrentHashMap<>();
    private final Set<String> gameSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> sessionCountByUsername = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> connectionVersionByUsername = new ConcurrentHashMap<>();

    @Autowired
    public GameConnectionEventListener(GameService gameService,
                                       TaskScheduler taskScheduler,
                                       GameWebSocketService webSocketService,
                                       ActiveWebSocketSessionRegistry activeSessionRegistry) {
        this.gameService = gameService;
        this.taskScheduler = taskScheduler;
        this.webSocketService = webSocketService;
        this.activeSessionRegistry = activeSessionRegistry;
    }

    // Kept for focused connection-count tests.
    GameConnectionEventListener(GameService gameService, TaskScheduler taskScheduler) {
        this(gameService, taskScheduler, null, null);
    }

    @EventListener
    public void onConnecting(SessionConnectEvent event) {
        registerFromMessage(event.getMessage());
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        registerFromMessage(event.getMessage());
    }

    private void registerFromMessage(org.springframework.messaging.Message<byte[]> message) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        Principal principal = accessor.getUser();
        String sessionId = accessor.getSessionId();
        if (principal == null || sessionId == null) {
            return;
        }

        Map<String, Object> attributes = accessor.getSessionAttributes();
        Long userId = attributes != null && attributes.get("userId") instanceof Long value ? value : null;
        Object tokenValue = attributes != null ? attributes.get(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE) : null;
        String loginToken = tokenValue instanceof String value ? value : null;
        registerConnectedSession(sessionId, principal.getName(), userId, loginToken);
    }

    void registerConnectedSession(String sessionId, String username) {
        registerConnectedSession(sessionId, username, null, null);
    }

    void registerConnectedSession(String sessionId, String username, Long userId, String loginToken) {
        if (sessionId == null || username == null || username.isBlank()) {
            return;
        }
        if (usernameBySession.putIfAbsent(sessionId, username) != null) {
            return;
        }
        if (activeSessionRegistry != null) {
            activeSessionRegistry.register(sessionId, username, userId, loginToken);
        }
        log.info("[WS] tracked session user={} sessionId={}", username, sessionId);
    }

    @EventListener
    public void onSubscribed(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (!isGameDestination(destination)) {
            return;
        }
        registerGameSubscription(accessor.getSessionId());
    }

    void registerGameSubscription(String sessionId) {
        if (sessionId == null || !gameSessionIds.add(sessionId)) {
            return;
        }
        String username = usernameBySession.get(sessionId);
        if (username == null) {
            gameSessionIds.remove(sessionId);
            return;
        }
        connectionVersionByUsername
                .computeIfAbsent(username, ignored -> new AtomicLong())
                .incrementAndGet();
        int sessionCount = sessionCountByUsername
                .computeIfAbsent(username, ignored -> new AtomicInteger())
                .incrementAndGet();
        if (sessionCount == 1) {
            gameService.playerConnected(username);
        }
        log.info("[WS] tracked game connection user={} sessions={}", username, sessionCount);
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        registerDisconnectedSession(event.getSessionId());
    }

    @EventListener
    public void onLoginSessionReplaced(LoginSessionReplacedEvent event) {
        if (activeSessionRegistry == null || webSocketService == null) {
            return;
        }
        for (ActiveWebSocketSessionRegistry.Connection connection
                : activeSessionRegistry.findByLoginToken(event.replacedLoginToken())) {
            webSocketService.sendSessionReplaced(connection.username(), connection.sessionId());
            registerDisconnectedSession(connection.sessionId());
        }
    }

    void registerDisconnectedSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String username = usernameBySession.remove(sessionId);
        if (activeSessionRegistry != null) {
            activeSessionRegistry.remove(sessionId);
        }
        if (username == null) {
            return;
        }
        if (!gameSessionIds.remove(sessionId)) {
            log.info("[WS] ignored non-game session close user={} sessionId={}", username, sessionId);
            return;
        }

        AtomicInteger counter = sessionCountByUsername.get(username);
        int remaining = counter == null ? 0 : counter.decrementAndGet();
        if (remaining <= 0) {
            sessionCountByUsername.remove(username, counter);
            long expectedVersion = connectionVersionByUsername
                    .computeIfAbsent(username, ignored -> new AtomicLong())
                    .incrementAndGet();
            taskScheduler.schedule(
                    () -> confirmStillDisconnected(username, expectedVersion),
                    Instant.now().plusMillis(CONNECTION_REPLACEMENT_WINDOW_MS)
            );
            remaining = 0;
        }
        log.info("[WS] tracked disconnection user={} sessions={}", username, remaining);
    }

    private void confirmStillDisconnected(String username, long expectedVersion) {
        AtomicLong version = connectionVersionByUsername.get(username);
        AtomicInteger sessionCount = sessionCountByUsername.get(username);
        boolean replacementConnected = version == null
                || version.get() != expectedVersion
                || (sessionCount != null && sessionCount.get() > 0);
        if (replacementConnected) {
            log.info("[WS] disconnect ignored after connection replacement user={}", username);
            return;
        }

        log.info("[WS] confirmed disconnected user={} replacementWindowMs={}",
                username, CONNECTION_REPLACEMENT_WINDOW_MS);
        gameService.playerDisconnected(username);
    }

    private boolean isGameDestination(String destination) {
        return destination != null && (destination.startsWith("/topic/rooms/")
                || destination.startsWith("/topic/games/")
                || destination.startsWith("/user/queue/room/"));
    }
}
