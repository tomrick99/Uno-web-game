package com.uno.controller;

import com.uno.dto.response.ApiResponse;
import com.uno.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameControllerReconnectTest {

    @Test
    void authenticatedReconnectReturnsRestoredSnapshotForTheSameUser() {
        GameService gameService = mock(GameService.class);
        GameController controller = new GameController(gameService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", 7L);
        Map<String, Object> snapshot = Map.of(
                "reconnected", true,
                "restoredPlayer", Map.of("userId", 7L, "gamePlayerId", 99L));
        when(gameService.reconnectPlayer(12L, 7L)).thenReturn(snapshot);

        ApiResponse<Map<String, Object>> response = controller.reconnectPlayer(12L, session);

        assertEquals(200, response.getCode());
        assertEquals(snapshot, response.getData());
        verify(gameService).reconnectPlayer(12L, 7L);
    }

    @Test
    void anonymousReconnectIsRejectedWithoutTouchingGameState() {
        GameService gameService = mock(GameService.class);
        GameController controller = new GameController(gameService);

        ApiResponse<Map<String, Object>> response = controller.reconnectPlayer(12L, new MockHttpSession());

        assertEquals(401, response.getCode());
    }
}
