package com.uno.controller;

import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.session.ActiveLoginService;
import com.uno.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SingleActiveLoginControllerTest {

    @Test
    void firstLoginWorksSecondLoginInvalidatesFirstAndNewDeviceWorks() {
        UserService userService = mock(UserService.class);
        User alice = new User("alice", "encoded");
        alice.setId(7L);
        when(userService.login("alice", "secret")).thenReturn(Optional.of(alice));
        when(userService.findById(7L)).thenReturn(Optional.of(alice));
        ActiveLoginService activeLoginService = new ActiveLoginService(event -> { });
        UserController controller = new UserController(userService, activeLoginService);
        MockHttpSession deviceA = new MockHttpSession();
        MockHttpSession deviceB = new MockHttpSession();

        assertEquals(200, controller.login(login("alice", "secret"), deviceA).getCode());
        assertEquals(200, controller.getCurrentUser(deviceA).getCode());

        assertEquals(200, controller.login(login("alice", "secret"), deviceB).getCode());

        ApiResponse<User> oldDevice = controller.getCurrentUser(deviceA);
        assertEquals(401, oldDevice.getCode());
        assertEquals(ActiveLoginService.REPLACED_MESSAGE, oldDevice.getMessage());
        assertEquals(200, controller.getCurrentUser(deviceB).getCode());
    }

    private com.uno.dto.request.LoginRequest login(String username, String password) {
        com.uno.dto.request.LoginRequest request = new com.uno.dto.request.LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
