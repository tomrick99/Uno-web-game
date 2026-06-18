package com.uno.controller;

import com.uno.dto.request.CreateRoomRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.RoomStatus;
import com.uno.service.GameService;
import com.uno.service.RoomService;
import com.uno.service.UserService;
import com.uno.websocket.GameWebSocketService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomControllerTest {

    @Test
    void createRoomImmediatelyJoinsHostAndReturnsAccuratePlayerCount() {
        HttpSession session = mock(HttpSession.class);

        User host = new User("alice", "pw");
        host.setId(7L);
        Room room = new Room();
        room.setId(42L);
        room.setHost(host);
        room.setStatus(RoomStatus.WAITING);
        room.setMaxPlayers(2);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);

        Map<String, Object> roomState = new LinkedHashMap<>();
        roomState.put("roomId", 42L);
        roomState.put("playerCount", 1);
        roomState.put("maxPlayers", 2);

        FakeRoomService roomService = new FakeRoomService(room, roomState);
        FakeUserService userService = new FakeUserService(host);
        FakeGameService gameService = new FakeGameService();
        FakeWebSocketService wsService = new FakeWebSocketService();
        RoomController controller = new RoomController(roomService, userService, gameService, wsService);

        when(session.getAttribute("userId")).thenReturn(7L);

        CreateRoomRequest request = new CreateRoomRequest();
        request.setMaxPlayers(2);
        request.setTotalRounds(8);
        request.setRoundTimeLimitMinutes(10);
        request.setGameMode(GameMode.CLASSIC);

        ApiResponse<Map<String, Object>> response = controller.createRoom(request, session);

        assertEquals(200, response.getCode());
        assertEquals(1, response.getData().get("playerCount"));
        assertEquals(42L, gameService.joinedRoomId);
        assertEquals(7L, gameService.joinedUserId);
        assertTrue(wsService.lobbyBroadcasted);
        assertEquals("ROOM_CREATED", wsService.lastEvent);
        assertEquals(roomState, wsService.lastRoomState);
    }

    private static final class FakeRoomService extends RoomService {
        private final Room room;
        private final Map<String, Object> roomState;

        private FakeRoomService(Room room, Map<String, Object> roomState) {
            super(null, null, null);
            this.room = room;
            this.roomState = roomState;
        }

        @Override
        public Room createRoom(User host, int maxPlayers, int totalRounds, int roundTimeLimitMinutes, GameMode gameMode) {
            return room;
        }

        @Override
        public Map<String, Object> getRoomState(Long roomId) {
            return roomState;
        }
    }

    private static final class FakeUserService extends UserService {
        private final User user;

        private FakeUserService(User user) {
            super(null);
            this.user = user;
        }

        @Override
        public Optional<User> findById(Long id) {
            return Optional.of(user);
        }
    }

    private static final class FakeGameService extends GameService {
        private Long joinedRoomId;
        private Long joinedUserId;

        private FakeGameService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public Map<String, Object> joinGame(Long roomId, Long userId) {
            joinedRoomId = roomId;
            joinedUserId = userId;
            return Map.of();
        }
    }

    private static final class FakeWebSocketService extends GameWebSocketService {
        private boolean lobbyBroadcasted;
        private String lastEvent;
        private Map<String, Object> lastRoomState;

        private FakeWebSocketService() {
            super(null);
        }

        @Override
        public void broadcastLobbyRoomState(Map<String, Object> roomState, String event, String message) {
            lobbyBroadcasted = true;
            lastEvent = event;
            lastRoomState = roomState;
        }
    }
}
