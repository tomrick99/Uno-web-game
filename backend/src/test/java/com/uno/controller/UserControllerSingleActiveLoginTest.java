package com.uno.controller;

import com.uno.auth.ActiveLoginService;
import com.uno.dto.request.LoginRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerSingleActiveLoginTest {

    @Test
    void firstLoginSucceedsAndSecondDeviceBecomesTheOnlyActiveLogin() {
        UserService userService = mock(UserService.class);
        User alice = new User("alice", "encoded");
        alice.setId(1L);
        when(userService.login("alice", "secret")).thenReturn(Optional.of(alice));
        when(userService.findById(1L)).thenReturn(Optional.of(alice));
        ActiveLoginService activeLoginService = new ActiveLoginService(ignored -> { });
        UserController controller = new UserController(userService, activeLoginService);
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret");
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();

        ApiResponse<User> firstLogin = controller.login(request, deviceA);
        ApiResponse<User> secondLogin = controller.login(request, deviceB);

        assertEquals(200, firstLogin.getCode());
        assertEquals(200, secondLogin.getCode());
        assertTrue(activeLoginService.isActive(deviceB));
        assertEquals(401, controller.getCurrentUser(deviceA).getCode());
        assertEquals(ActiveLoginService.SESSION_REPLACED_MESSAGE,
                controller.getCurrentUser(deviceA).getMessage());
        assertEquals(200, controller.getCurrentUser(deviceB).getCode());
    }
}
