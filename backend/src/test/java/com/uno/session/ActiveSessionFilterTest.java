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

class ActiveSessionFilterTest {

    @Test
    void replacedSessionCannotReachAuthenticatedGameOrLobbyOperations() throws Exception {
        ActiveLoginService loginService = new ActiveLoginService(event -> { });
        ActiveSessionFilter filter = new ActiveSessionFilter(loginService, new ObjectMapper());
        User alice = user(1L, "alice");
        MockHttpSession oldSession = new MockHttpSession();
        MockHttpSession newSession = new MockHttpSession();
        loginService.activate(alice, oldSession);
        loginService.activate(alice, newSession);

        for (String path : new String[] {
                "/api/user/me",
                "/api/room/list",
                "/api/room/create",
                "/api/room/join",
                "/api/game/9/join",
                "/api/game/9/play",
                "/api/game/9/draw"
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRequestURI(path);
            request.setSession(oldSession);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertEquals(401, response.getStatus(), path);
            assertTrue(response.getContentAsString().contains(ActiveLoginService.REPLACED_MESSAGE), path);
            verify(chain, never()).doFilter(request, response);
        }
    }

    @Test
    void currentSessionAndDifferentAccountContinueNormally() throws Exception {
        ActiveLoginService loginService = new ActiveLoginService(event -> { });
        ActiveSessionFilter filter = new ActiveSessionFilter(loginService, new ObjectMapper());
        MockHttpSession alice = new MockHttpSession();
        MockHttpSession bob = new MockHttpSession();
        loginService.activate(user(1L, "alice"), alice);
        loginService.activate(user(2L, "bob"), bob);

        for (MockHttpSession session : new MockHttpSession[] { alice, bob }) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/game/9/draw");
            request.setRequestURI("/api/game/9/draw");
            request.setSession(session);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            assertEquals(200, response.getStatus());
        }
    }

    private User user(Long id, String username) {
        User user = new User(username, "encoded");
        user.setId(id);
        return user;
    }
}
