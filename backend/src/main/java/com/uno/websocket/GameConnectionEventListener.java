package com.uno.websocket;

import com.uno.session.ActiveLoginService;
import com.uno.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashSet;
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
    private final ActiveWebSocketRegistry webSocketRegistry;
    private final Map<String, String> usernameBySession = new ConcurrentHashMap<>();
    private final Map<String, String> loginTokenBySession = new ConcurrentHashMap<>();
    private final Set<String> gameSessionIds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> sessionCountByUsername = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> connectionVersionByUsername = new ConcurrentHashMap<>();
    private final Map<String, String> activeGameSessionByUsername = new ConcurrentHashMap<>();

    @Autowired
    public GameConnectionEventListener(GameService gameService,
                                       TaskScheduler taskScheduler,
                                       ActiveWebSocketRegistry webSocketRegistry) {
        this.gameService = gameService;
        this.taskScheduler = taskScheduler;
        this.webSocketRegistry = webSocketRegistry;
    }

    GameConnectionEventListener(GameService gameService, TaskScheduler taskScheduler) {
        this(gameService, taskScheduler, null);
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = accessor.getUser();
        String sessionId = accessor.getSessionId();
        if (principal == null || sessionId == null) {
            return;
        }

        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object loginToken = attributes == null
                ? null
                : attributes.get(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
        if (!(loginToken instanceof String) && webSocketRegistry != null) {
            loginToken = webSocketRegistry.loginTokenForSession(sessionId);
        }
        registerConnectedSession(
                sessionId,
                principal.getName(),
                loginToken instanceof String token ? token : null
        );
    }

    void registerConnectedSession(String sessionId, String username) {
        registerConnectedSession(sessionId, username, null);
    }

    void registerConnectedSession(String sessionId, String username, String loginToken) {
        if (sessionId == null || username == null || username.isBlank()) {
            return;
        }
        if (usernameBySession.putIfAbsent(sessionId, username) != null) {
            return;
        }
        if (loginToken != null && !loginToken.isBlank()) {
            loginTokenBySession.put(sessionId, loginToken);
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
        String previousSessionId = activeGameSessionByUsername.put(username, sessionId);
        if (previousSessionId != null && !previousSessionId.equals(sessionId)) {
            registerDisconnectedSession(previousSessionId);
            if (webSocketRegistry != null) {
                webSocketRegistry.closeSession(previousSessionId, CloseStatus.NORMAL);
            }
            log.info("[WS] replaced duplicate game connection user={} oldSessionId={} newSessionId={}",
                    username, previousSessionId, sessionId);
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

    void registerDisconnectedSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String username = usernameBySession.remove(sessionId);
        loginTokenBySession.remove(sessionId);
        if (username == null) {
            return;
        }
        if (!gameSessionIds.remove(sessionId)) {
            log.info("[WS] ignored non-game session close user={} sessionId={}", username, sessionId);
            return;
        }

        activeGameSessionByUsername.remove(username, sessionId);

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

    Set<String> revokeLoginToken(String loginToken) {
        Set<String> sessionIds = new LinkedHashSet<>();
        loginTokenBySession.forEach((sessionId, token) -> {
            if (loginToken.equals(token)) {
                sessionIds.add(sessionId);
            }
        });
        sessionIds.forEach(this::registerDisconnectedSession);
        return sessionIds;
    }

    void revokeSessions(Set<String> sessionIds) {
        sessionIds.forEach(this::registerDisconnectedSession);
    }

    int activeGameConnectionCount(String username) {
        return activeGameSessionByUsername.containsKey(username) ? 1 : 0;
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
