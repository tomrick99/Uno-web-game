package com.uno.websocket;

import com.uno.service.DisconnectGracePeriodService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

@Component
public class GameConnectionEventListener {

    private static final String GAME_TOPIC_PREFIX = "/topic/games/";

    private final DisconnectGracePeriodService disconnectGracePeriodService;

    public GameConnectionEventListener(DisconnectGracePeriodService disconnectGracePeriodService) {
        this.disconnectGracePeriodService = disconnectGracePeriodService;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        Principal user = accessor.getUser();
        if (destination == null || user == null || !destination.startsWith(GAME_TOPIC_PREFIX)) {
            return;
        }
        try {
            Long gameId = Long.valueOf(destination.substring(GAME_TOPIC_PREFIX.length()));
            disconnectGracePeriodService.gameSessionSubscribed(
                    accessor.getSessionId(), user.getName(), gameId);
        } catch (NumberFormatException ignored) {
            // Ignore malformed or non-game subscriptions.
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        disconnectGracePeriodService.gameSessionDisconnected(event.getSessionId());
    }
}
