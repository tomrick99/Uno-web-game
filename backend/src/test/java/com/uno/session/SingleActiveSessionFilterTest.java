package com.uno.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uno.entity.User;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SingleActiveSessionFilterTest {

    @Test
    void replacedDeviceCannotReachAuthenticatedOperationWhileNewDeviceCan() throws Exception {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        SingleActiveSessionFilter filter = new SingleActiveSessionFilter(registry, new ObjectMapper());
        User alice = user(1L, "alice");
        MockHttpSession oldSession = new MockHttpSession();
        oldSession.setAttribute("userId", alice.getId());
        MockHttpSession newSession = new MockHttpSession();
        newSession.setAttribute("userId", alice.getId());
        String oldSessionId = oldSession.getId();
        registry.activate(alice, oldSession);
        registry.activate(alice, newSession);

        for (String protectedOperation : new String[]{
                "/api/user/me",
                "/api/room/list",
                "/api/room/create",
                "/api/room/join",
                "/api/game/9/play"
        }) {
            MockHttpServletRequest oldRequest = new MockHttpServletRequest("POST", protectedOperation);
            oldRequest.setRequestedSessionId(oldSessionId);
            MockHttpServletResponse oldResponse = new MockHttpServletResponse();
            FilterChain oldChain = mock(FilterChain.class);
            filter.doFilter(oldRequest, oldResponse, oldChain);

            assertEquals(401, oldResponse.getStatus(), protectedOperation);
            assertEquals("true", oldResponse.getHeader(SessionPolicy.REPLACED_HEADER));
            assertTrue(oldResponse.getContentAsString().contains(SessionPolicy.REPLACED_MESSAGE));
            verify(oldChain, never()).doFilter(oldRequest, oldResponse);
        }

        MockHttpServletRequest newRequest = new MockHttpServletRequest("POST", "/api/game/9/play");
        newRequest.setSession(newSession);
        MockHttpServletResponse newResponse = new MockHttpServletResponse();
        FilterChain newChain = mock(FilterChain.class);
        filter.doFilter(newRequest, newResponse, newChain);

        verify(newChain).doFilter(newRequest, newResponse);
    }

    private User user(Long id, String username) {
        User user = new User(username, "password");
        user.setId(id);
        return user;
    }
}
