package com.uno.websocket;

import com.uno.entity.User;
import com.uno.session.ActiveSessionRegistry;
import com.uno.session.SessionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ActiveSessionChannelInterceptorTest {

    @Test
    void staleConnectionCannotSendAuthenticatedStompFramesButCurrentConnectionCan() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        ActiveSessionChannelInterceptor interceptor = new ActiveSessionChannelInterceptor(registry);
        User alice = new User("alice", "password");
        alice.setId(1L);
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();
        registry.activate(alice, deviceA);
        Message<byte[]> oldFrame = frame("alice", deviceA.getId());
        registry.activate(alice, deviceB);
        Message<byte[]> currentFrame = frame("alice", deviceB.getId());

        MessageChannel channel = mock(MessageChannel.class);
        assertThrows(AccessDeniedException.class, () -> interceptor.preSend(oldFrame, channel));
        assertDoesNotThrow(() -> interceptor.preSend(currentFrame, channel));
    }

    private Message<byte[]> frame(String username, String httpSessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setUser(() -> username);
        accessor.setSessionAttributes(Map.of(SessionPolicy.HTTP_SESSION_ID_ATTRIBUTE, httpSessionId));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
