package com.uno.websocket;

import com.uno.session.SessionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveWebSocketSessionRegistryTest {

    @Test
    void replacementClosesOnlySocketsBelongingToTheOldHttpSession() throws Exception {
        ActiveWebSocketSessionRegistry registry = new ActiveWebSocketSessionRegistry();
        WebSocketSession oldSocket = socket("ws-old", "http-old");
        WebSocketSession otherSocket = socket("ws-other", "http-other");
        registry.register(oldSocket);
        registry.register(otherSocket);

        registry.closeForHttpSession("http-old");

        verify(oldSocket).close(argThat(status ->
                status.getCode() == SessionPolicy.REPLACED_WEBSOCKET_CLOSE_CODE
                        && SessionPolicy.REPLACED_MESSAGE.equals(status.getReason())));
        assertEquals(0, registry.countForHttpSession("http-old"));
        assertEquals(1, registry.countForHttpSession("http-other"));
    }

    private WebSocketSession socket(String socketId, String httpSessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(socketId);
        when(session.getAttributes()).thenReturn(Map.of(
                SessionPolicy.HTTP_SESSION_ID_ATTRIBUTE, httpSessionId));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
