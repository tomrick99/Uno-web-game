package com.uno.auth;

import com.uno.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ActiveLoginChannelInterceptorTest {

    @Test
    void replacedLoginCannotSendWebSocketOperations() {
        ActiveLoginService service = new ActiveLoginService(ignored -> { });
        ActiveLoginChannelInterceptor interceptor = new ActiveLoginChannelInterceptor(service);
        User alice = new User("alice", "encoded");
        alice.setId(1L);
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();
        service.activate(alice, deviceA);
        service.activate(alice, deviceB);

        Message<byte[]> oldMessage = stompMessage(deviceA);
        Message<byte[]> newMessage = stompMessage(deviceB);
        MessageChannel channel = mock(MessageChannel.class);

        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(oldMessage, channel));
        assertSame(newMessage, interceptor.preSend(newMessage, channel));
    }

    private Message<byte[]> stompMessage(MockHttpSession session) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId("ws-" + session.getId());
        accessor.setSessionAttributes(Map.of(
                "userId", session.getAttribute("userId"),
                ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE,
                session.getAttribute(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE)
        ));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
