package com.uno.service;

import com.uno.entity.User;
import com.uno.repository.GamePlayerRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LobbyActivityNotificationServiceTest {

    @Test
    void publishesActorAndAllRoomParticipantsAsExcludedRecipients() {
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        GameWebSocketService webSocketService = mock(GameWebSocketService.class);
        User alice = user(1L, "alice");
        User carol = user(3L, "carol");
        com.uno.entity.GamePlayer carolPlayer = new com.uno.entity.GamePlayer();
        carolPlayer.setUser(carol);
        when(gamePlayerRepository.findAll()).thenReturn(List.of(carolPlayer));

        LobbyActivityNotificationService service = new LobbyActivityNotificationService(
                gamePlayerRepository, webSocketService);
        Map<String, Object> roomState = Map.of("roomId", 10L, "roomCode", "ABC123");

        service.notifyRoomActivity(roomState, "ROOM_CREATED", alice);

        verify(webSocketService).broadcastLobbyActivityNotification(
                roomState, "ROOM_CREATED", alice, Set.of(1L, 3L));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
