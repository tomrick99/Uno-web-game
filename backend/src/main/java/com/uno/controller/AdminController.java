package com.uno.controller;

import com.uno.dto.request.CreateRoomRequest;
import com.uno.dto.response.ApiResponse;
import com.uno.entity.User;
import com.uno.service.GameService;
import com.uno.service.RoomService;
import com.uno.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RoomService roomService;
    private final GameService gameService;
    private final UserService userService;

    public AdminController(RoomService roomService, GameService gameService, UserService userService) {
        this.roomService = roomService;
        this.gameService = gameService;
        this.userService = userService;
    }

    private User getCurrentUser(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        return userService.findById(userId).orElse(null);
    }

    private boolean isAdmin(User user) {
        return user != null && "admin".equalsIgnoreCase(user.getUsername());
    }

    @GetMapping("/rooms")
    public ApiResponse<List<Map<String, Object>>> getAllRooms(HttpSession session) {
        User user = getCurrentUser(session);
        if (!isAdmin(user)) {
            return ApiResponse.error(403, "Only admins can access this page");
        }
        return ApiResponse.success(roomService.getAllRoomStates());
    }

    @PutMapping("/rooms/{roomId}")
    public ApiResponse<Map<String, Object>> updateRoom(@PathVariable Long roomId,
                                                       @RequestBody CreateRoomRequest request,
                                                       HttpSession session) {
        User user = getCurrentUser(session);
        if (!isAdmin(user)) {
            return ApiResponse.error(403, "Only admins can edit rooms");
        }

        try {
            return ApiResponse.success("Room updated", roomService.updateRoomConfigByAdmin(
                    roomId,
                    request.getMaxPlayers(),
                    request.getTotalRounds(),
                    request.getRoundTimeLimitMinutes(),
                    request.getGameMode()
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/rooms/{roomId}")
    public ApiResponse<Void> deleteRoom(@PathVariable Long roomId, HttpSession session) {
        User user = getCurrentUser(session);
        if (!isAdmin(user)) {
            return ApiResponse.error(403, "Only admins can delete rooms");
        }

        try {
            gameService.deleteRoomByAdmin(roomId, user.getUsername());
            return ApiResponse.success("Room deleted", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }
}
