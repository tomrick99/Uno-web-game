package com.uno.controller;

import com.uno.dto.response.ApiResponse;
import com.uno.entity.enums.CardColor;
import com.uno.service.GameService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    private Long getCurrentUserId(HttpSession session) {
        return (Long) session.getAttribute("userId");
    }

    private ApiResponse<Map<String, Object>> unauthorized() {
        return ApiResponse.error(401, "Please log in first");
    }

    @PostMapping("/{roomId}/join")
    public ApiResponse<Map<String, Object>> joinGame(@PathVariable Long roomId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success("Joined game", gameService.joinGame(roomId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/{gameId}/play")
    public ApiResponse<Map<String, Object>> playCard(@PathVariable Long gameId,
                                                     @RequestParam int cardIndex,
                                                     @RequestParam(required = false) String chosenColor,
                                                     HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            CardColor color = null;
            if (chosenColor != null && !chosenColor.isBlank()) {
                color = CardColor.valueOf(chosenColor.toUpperCase());
            }

            return ApiResponse.success("Card played", gameService.playCard(gameId, userId, cardIndex, color));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/{gameId}/draw")
    public ApiResponse<Map<String, Object>> drawCard(@PathVariable Long gameId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success("Card drawn", gameService.drawCard(gameId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping({"/{gameId}/draw-penalty", "/{gameId}/drawPenalty"})
    public ApiResponse<Map<String, Object>> drawPenalty(@PathVariable Long gameId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success("Penalty drawn", gameService.drawPenalty(gameId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/{gameId}/rematch-ready")
    public ApiResponse<Map<String, Object>> rematchReady(@PathVariable Long gameId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success("Rematch ready updated", gameService.readyForRematch(gameId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @PostMapping("/{gameId}/restart")
    public ApiResponse<Map<String, Object>> restartGame(@PathVariable Long gameId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success("Game restarted", gameService.restartGame(gameId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}/state")
    public ApiResponse<Map<String, Object>> getGameStateByRoom(@PathVariable Long roomId) {
        try {
            return ApiResponse.success(gameService.getGameStateByRoomId(roomId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}/hand")
    public ApiResponse<List<Map<String, Object>>> getMyHandByRoom(@PathVariable Long roomId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return ApiResponse.error(401, "Please log in first");
        }

        try {
            return ApiResponse.success(gameService.getPlayerHandByRoomId(roomId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}/snapshot")
    public ApiResponse<Map<String, Object>> getRealtimeSnapshotByRoom(@PathVariable Long roomId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return unauthorized();
        }

        try {
            return ApiResponse.success(gameService.getRealtimeSnapshotByRoomId(roomId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    @GetMapping("/{gameId}/state")
    public ApiResponse<Map<String, Object>> getGameState(@PathVariable Long gameId) {
        try {
            return ApiResponse.success(gameService.getGameState(gameId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/{gameId}/hand")
    public ApiResponse<List<Map<String, Object>>> getMyHand(@PathVariable Long gameId, HttpSession session) {
        Long userId = getCurrentUserId(session);
        if (userId == null) {
            return ApiResponse.error(401, "Please log in first");
        }

        try {
            return ApiResponse.success(gameService.getPlayerHand(gameId, userId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
