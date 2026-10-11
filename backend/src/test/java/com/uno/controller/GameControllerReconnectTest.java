package com.uno.controller;

import com.uno.dto.response.ApiResponse;
import com.uno.service.GameService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GameControllerReconnectTest {

    @Test
    void reconnectUsesAuthenticatedAccountAndReturnsFullSnapshot() {
        GameService gameService = mock(GameService.class);
        HttpSession session = mock(HttpSession.class);
        GameController controller = new GameController(gameService);
        Map<String, Object> snapshot = Map.of(
                "type", "FULL_SNAPSHOT",
                "gameState", Map.of("disconnectGraceActive", false),
                "handCards", java.util.List.of(Map.of("color", "GREEN"))
        );
        when(session.getAttribute("userId")).thenReturn(2L);
        when(gameService.reconnectPlayer(10L, 2L)).thenReturn(snapshot);

        ApiResponse<Map<String, Object>> response = controller.reconnectPlayer(10L, session);

        assertEquals(200, response.getCode());
        assertSame(snapshot, response.getData());
        verify(gameService).reconnectPlayer(10L, 2L);
    }

    @Test
    void reconnectRequiresAnAuthenticatedAccount() {
        GameService gameService = mock(GameService.class);
        HttpSession session = mock(HttpSession.class);
        GameController controller = new GameController(gameService);

        ApiResponse<Map<String, Object>> response = controller.reconnectPlayer(10L, session);

        assertEquals(401, response.getCode());
        verifyNoInteractions(gameService);
    }
}
