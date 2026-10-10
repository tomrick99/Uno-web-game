package com.uno.controller;

import com.uno.dto.request.LoginRequest;
import com.uno.entity.User;
import com.uno.service.UserService;
import com.uno.session.ActiveSessionRegistry;
import com.uno.session.SessionReplacementNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerSingleSessionTest {

    @Test
    void firstLoginWorksAndSecondLoginBecomesTheOnlyActiveSession() {
        UserService userService = mock(UserService.class);
        SessionReplacementNotifier notifier = mock(SessionReplacementNotifier.class);
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        User alice = new User("alice", "encoded");
        alice.setId(1L);
        when(userService.login("alice", "secret")).thenReturn(Optional.of(alice));
        when(userService.findById(1L)).thenReturn(Optional.of(alice));
        UserController controller = new UserController(userService, registry, notifier);
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret");
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();

        assertEquals(200, controller.login(request, deviceA).getCode());
        assertTrue(registry.isActive(1L, deviceA.getId()));

        assertEquals(200, controller.login(request, deviceB).getCode());
        assertTrue(registry.isActive(1L, deviceB.getId()));
        assertEquals(200, controller.getCurrentUser(deviceB).getCode());
        verify(notifier).notifyReplacement(argThat(replaced ->
                replaced.username().equals("alice") && replaced.sessionId().equals(deviceA.getId())));
    }
}
