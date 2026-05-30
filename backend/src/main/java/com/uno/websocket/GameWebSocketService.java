package com.uno.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GameWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketService.class);

    private final SimpMessagingTemplate messagingTemplate;

    public GameWebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastRoomState(Map<String, Object> roomState, String event, String message) {
        Object roomId = roomState.get("roomId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ROOM_STATE");
        payload.put("event", event);
        payload.put("roomId", roomId);
        payload.put("roomState", roomState);
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        messagingTemplate.convertAndSend("/topic/rooms/" + roomId, payload);
        Object players = roomState.get("playerCount");
        log.info("[UNO] broadcast roomState roomId={} players={} event={}", roomId, players, event);
    }

    public void broadcastRoomDeleted(Long roomId, Long gameId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ROOM_DELETED");
        payload.put("event", "ROOM_DELETED");
        payload.put("roomId", roomId);
        payload.put("gameId", gameId);
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        messagingTemplate.convertAndSend("/topic/rooms/" + roomId, payload);
        if (gameId != null) {
            messagingTemplate.convertAndSend("/topic/games/" + gameId, payload);
        }
        log.info("[WS] ROOM_DELETED -> roomId={}, gameId={}", roomId, gameId);
    }

    public void broadcastGameState(Map<String, Object> gameState, String event, String message) {
        Object gameId = gameState.get("gameId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "GAME_STATE");
        payload.put("event", event);
        payload.put("gameId", gameId);
        payload.put("gameState", gameState);
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        messagingTemplate.convertAndSend("/topic/games/" + gameId, payload);
        log.info("[UNO] broadcast gameState gameId={} phase={} currentTurn={} pendingDrawCount={} event={}",
                gameId,
                gameState.get("status"),
                gameState.get("currentTurn"),
                gameState.get("pendingDrawCount"),
                event);
    }

    public void broadcastGameOver(Map<String, Object> gameState, Long winnerId, String message) {
        Object gameId = gameState.get("gameId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "GAME_OVER");
        payload.put("event", "GAME_OVER");
        payload.put("gameId", gameId);
        payload.put("winnerId", winnerId);
        payload.put("gameState", gameState);
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        messagingTemplate.convertAndSend("/topic/games/" + gameId, payload);
        log.info("[WS] GAME_OVER -> /topic/games/{} winnerId={}", gameId, winnerId);
    }

    public void broadcastPlayerHand(Long gameId, Long userId, List<Map<String, Object>> handCards, String event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "HAND_STATE");
        payload.put("event", event);
        payload.put("gameId", gameId);
        payload.put("userId", userId);
        payload.put("handCards", handCards);

        messagingTemplate.convertAndSend("/topic/games/" + gameId + "/hands/" + userId, payload);
        log.info("[WS] HAND_STATE -> /topic/games/{}/hands/{}", gameId, userId);
    }
}
