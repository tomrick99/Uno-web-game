package com.uno.controller;

import com.uno.dto.request.CreateRoomRequest;
import com.uno.dto.request.JoinRoomRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.service.GameService;
import com.uno.service.RoomService;
import com.uno.service.UserService;
import com.uno.websocket.GameWebSocketService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/room")
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final UserService userService;
    private final GameService gameService;
    private final GameWebSocketService wsService;

    public RoomController(RoomService roomService,
                          UserService userService,
                          GameService gameService,
                          GameWebSocketService wsService) {
        this.roomService = roomService;
        this.userService = userService;
        this.gameService = gameService;
        this.wsService = wsService;
    }

    private User getCurrentUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        return userService.findById(userId).orElse(null);
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createRoom(@RequestBody(required = false) CreateRoomRequest request, HttpSession session) {
        User user = getCurrentUser(session);
        if (user == null) {
            return ApiResponse.error(401, "Please log in first");
        }

        try {
            CreateRoomRequest safeRequest = request == null ? new CreateRoomRequest() : request;
            Room room = roomService.createRoom(
                    user,
                    safeRequest.getMaxPlayers(),
                    safeRequest.getTotalRounds(),
                    safeRequest.getRoundTimeLimitMinutes(),
                    safeRequest.getGameMode());
            gameService.joinGame(room.getId(), user.getId());
            Map<String, Object> roomState = roomService.getRoomState(room.getId());
            wsService.broadcastLobbyRoomState(roomState, "ROOM_CREATED", "Room created");
            return ApiResponse.success("Room created", roomState);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/join")
    public ApiResponse<String> joinRoom(@RequestBody JoinRoomRequest request, HttpSession session) {
        User user = getCurrentUser(session);
        if (user == null) {
            return ApiResponse.error(401, "Please log in first");
        }

        try {
            roomService.joinRoom(request.getRoomCode(), user);
            return ApiResponse.success("Joined room");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/{roomId}/leave")
    public ApiResponse<Map<String, Object>> leaveRoom(@PathVariable Long roomId, HttpSession session) {
        User user = getCurrentUser(session);
        if (user == null) {
            return ApiResponse.error(401, "Please log in first");
        }

        try {
            return ApiResponse.success("Left room", gameService.leaveRoom(roomId, user.getId()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> listRooms() {
        long startedAt = System.nanoTime();
        try {
            return ApiResponse.success(roomService.getWaitingRoomStates());
        } finally {
            long costMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[PERF] action=listRooms costMs={}", costMs);
        }
    }

    @GetMapping("/{roomCode}")
    public ApiResponse<Room> getRoom(@PathVariable String roomCode) {
        return roomService.findByRoomCode(roomCode)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "Room not found"));
    }
}
