package com.uno.websocket;

import com.uno.service.DisconnectGracePeriodService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GameConnectionEventListenerTest {

    private final DisconnectGracePeriodService gracePeriodService = mock(DisconnectGracePeriodService.class);
    private final GameConnectionEventListener listener = new GameConnectionEventListener(gracePeriodService);

    @Test
    void gameTopicSubscriptionRegistersAuthenticatedGameSession() {
        Principal alice = () -> "alice";

        listener.onSubscribe(new SessionSubscribeEvent(this,
                stompMessage(StompCommand.SUBSCRIBE, "session-1", "/topic/games/42", alice), alice));

        verify(gracePeriodService).gameSessionSubscribed("session-1", "alice", 42L);
    }

    @Test
    void unrelatedSubscriptionDoesNotRegisterGameSession() {
        Principal alice = () -> "alice";

        listener.onSubscribe(new SessionSubscribeEvent(this,
                stompMessage(StompCommand.SUBSCRIBE, "session-1", "/topic/rooms/42", alice), alice));

        verify(gracePeriodService, never()).gameSessionSubscribed("session-1", "alice", 42L);
    }

    @Test
    void stompSessionDisconnectIsForwardedToGracePeriodService() {
        Principal alice = () -> "alice";
        Message<byte[]> message = stompMessage(StompCommand.DISCONNECT, "session-1", null, alice);

        listener.onDisconnect(new SessionDisconnectEvent(
                this, message, "session-1", CloseStatus.NORMAL, alice));

        verify(gracePeriodService).gameSessionDisconnected("session-1");
    }

    private Message<byte[]> stompMessage(StompCommand command,
                                          String sessionId,
                                          String destination,
                                          Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        accessor.setUser(principal);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
