package com.uno.websocket;

import com.uno.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ConnectionPresenceRegistryTest {

    @Test
    void onlyLastSessionDisconnectMakesPlayerOffline() {
        ConnectionPresenceRegistry registry = new ConnectionPresenceRegistry();

        assertTrue(registry.connected("alice", "one"));
        assertFalse(registry.connected("alice", "two"));
        assertFalse(registry.disconnected("alice", "one"));
        assertTrue(registry.isConnected("alice"));
        assertTrue(registry.disconnected("alice", "two"));
        assertFalse(registry.isConnected("alice"));
    }

    @Test
    void websocketLifecycleOnlyNotifiesServiceForOnlineOfflineTransitions() {
        ConnectionPresenceRegistry registry = new ConnectionPresenceRegistry();
        GameService gameService = mock(GameService.class);
        GameWebSocketLifecycleListener listener = new GameWebSocketLifecycleListener(registry, gameService);
        Principal alice = () -> "alice";

        listener.onConnected(new SessionConnectEvent(this, gameMessage("one"), alice));
        listener.onConnected(new SessionConnectEvent(this, gameMessage("two"), alice));
        listener.onDisconnected(new SessionDisconnectEvent(this, disconnectMessage("one"), "one", CloseStatus.NORMAL, alice));
        listener.onDisconnected(new SessionDisconnectEvent(this, disconnectMessage("two"), "two", CloseStatus.NORMAL, alice));

        verify(gameService, times(1)).handlePlayerReconnected("alice");
        verify(gameService, times(1)).handlePlayerDisconnected("alice");
    }

    @Test
    void fourPlayersMovingFromLobbyToGameDoNotEmitFalseDisconnects() {
        ConnectionPresenceRegistry registry = new ConnectionPresenceRegistry();
        GameService gameService = mock(GameService.class);
        GameWebSocketLifecycleListener listener = new GameWebSocketLifecycleListener(registry, gameService);

        for (int i = 1; i <= 4; i++) {
            int playerNumber = i;
            Principal player = () -> "player-" + playerNumber;
            String lobbySession = "lobby-" + i;
            String gameSession = "game-" + i;
            listener.onConnected(new SessionConnectEvent(this, lobbyMessage(lobbySession), player));
            listener.onDisconnected(new SessionDisconnectEvent(
                    this, disconnectMessage(lobbySession), lobbySession, CloseStatus.NORMAL, player));
            listener.onConnected(new SessionConnectEvent(this, gameMessage(gameSession), player));
        }

        verify(gameService, never()).handlePlayerDisconnected(org.mockito.ArgumentMatchers.anyString());
        verify(gameService, times(4)).handlePlayerReconnected(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void lateLobbyDisconnectCannotRemoveAnEstablishedGameSession() {
        ConnectionPresenceRegistry registry = new ConnectionPresenceRegistry();
        GameService gameService = mock(GameService.class);
        GameWebSocketLifecycleListener listener = new GameWebSocketLifecycleListener(registry, gameService);
        Principal alice = () -> "alice";

        listener.onConnected(new SessionConnectEvent(this, lobbyMessage("lobby"), alice));
        listener.onConnected(new SessionConnectEvent(this, gameMessage("game"), alice));
        listener.onDisconnected(new SessionDisconnectEvent(
                this, disconnectMessage("lobby"), "lobby", CloseStatus.NORMAL, alice));

        verify(gameService, never()).handlePlayerDisconnected("alice");
        assertTrue(registry.isConnected("alice"));

        listener.onDisconnected(new SessionDisconnectEvent(
                this, disconnectMessage("game"), "game", CloseStatus.NORMAL, alice));
        verify(gameService, times(1)).handlePlayerDisconnected("alice");
    }

    private static Message<byte[]> gameMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        accessor.setNativeHeader(GameWebSocketLifecycleListener.CLIENT_TYPE_HEADER,
                GameWebSocketLifecycleListener.GAME_CLIENT_TYPE);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> lobbyMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> disconnectMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
