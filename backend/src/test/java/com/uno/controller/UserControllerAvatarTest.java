package com.uno.controller;

import com.uno.dto.request.UpdateAvatarRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.service.GameService;
import com.uno.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerAvatarTest {

    @Test
    void avatarUpdateDoesNotMutatePasswordOrExposeItInResponse() {
        UserService userService = mock(UserService.class);
        GameService gameService = mock(GameService.class);
        HttpSession session = mock(HttpSession.class);
        UserController controller = new UserController(userService, gameService);
        UpdateAvatarRequest request = new UpdateAvatarRequest();
        request.setAvatarDataUrl("data:image/png;base64,iVBORw0KGgo=");

        User user = new User("alice", "stored-password-hash");
        user.setId(7L);
        user.setAvatarDataUrl(request.getAvatarDataUrl());
        when(session.getAttribute("userId")).thenReturn(7L);
        when(userService.updateAvatar(7L, request.getAvatarDataUrl())).thenReturn(user);

        ApiResponse<Map<String, Object>> response = controller.updateAvatar(request, session);

        assertEquals(200, response.getCode());
        assertEquals("stored-password-hash", user.getPassword());
        assertEquals(request.getAvatarDataUrl(), response.getData().get("avatarDataUrl"));
        assertFalse(response.getData().containsKey("password"));
        verify(gameService).broadcastPlayerProfileUpdate(7L);
    }
}
