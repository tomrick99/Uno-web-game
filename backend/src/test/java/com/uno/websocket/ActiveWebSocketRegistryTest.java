package com.uno.websocket;

import com.uno.session.ActiveLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiveWebSocketRegistryTest {

    @Test
    void closingAReplacedLoginDoesNotCloseAnotherAccountsSocket() throws Exception {
        ActiveWebSocketRegistry registry = new ActiveWebSocketRegistry();
        WebSocketSession alice = session("alice-ws", "alice-login");
        WebSocketSession bob = session("bob-ws", "bob-login");
        registry.register(alice);
        registry.register(bob);
        CloseStatus status = CloseStatus.POLICY_VIOLATION.withReason("login replaced");

        assertEquals(java.util.Set.of("alice-ws"), registry.sessionIdsForLogin("alice-login"));
        assertEquals("alice-login", registry.loginTokenForSession("alice-ws"));

        registry.closeLogin("alice-login", status);

        verify(alice).close(status);
        verify(bob, never()).close(status);
    }

    private WebSocketSession session(String id, String loginToken) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(Map.of(
                ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE, loginToken));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
