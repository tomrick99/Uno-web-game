package com.uno.websocket;

import com.uno.service.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
public class GameWebSocketLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketLifecycleListener.class);
    static final String CLIENT_TYPE_HEADER = "x-uno-client";
    static final String GAME_CLIENT_TYPE = "game";

    private final ConnectionPresenceRegistry presenceRegistry;
    private final GameService gameService;

    public GameWebSocketLifecycleListener(ConnectionPresenceRegistry presenceRegistry, GameService gameService) {
        this.presenceRegistry = presenceRegistry;
        this.gameService = gameService;
    }

    @EventListener
    public void onConnected(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (!GAME_CLIENT_TYPE.equalsIgnoreCase(accessor.getFirstNativeHeader(CLIENT_TYPE_HEADER))) {
            return;
        }
        Principal principal = event.getUser() != null ? event.getUser() : accessor.getUser();
        String username = principal != null ? principal.getName() : null;
        String sessionId = accessor.getSessionId();
        if (presenceRegistry.connected(username, sessionId)) {
            log.info("[WS] game player online user={} session={}", username, sessionId);
            gameService.handlePlayerReconnected(username);
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal principal = event.getUser() != null ? event.getUser() : accessor.getUser();
        String username = principal != null ? principal.getName() : null;
        String sessionId = event.getSessionId() != null ? event.getSessionId() : accessor.getSessionId();
        if (presenceRegistry.disconnected(username, sessionId)) {
            log.info("[WS] game player offline user={} session={}", username, sessionId);
            gameService.handlePlayerDisconnected(username);
        }
    }
}
