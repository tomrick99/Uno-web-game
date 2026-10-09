package com.uno.websocket;

import com.uno.auth.ActiveLoginService;
import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.User;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GameWebSocketService {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketService.class);
    private static final String LOBBY_TOPIC = "/topic/lobby";
    private static final String LOBBY_ACTIVITY_QUEUE = "/queue/lobby-activity";

    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;
    private final UserRepository userRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final ActiveWebSocketSessionRegistry activeSessionRegistry;

    @Autowired
    public GameWebSocketService(SimpMessagingTemplate messagingTemplate,
                                SimpUserRegistry userRegistry,
                                UserRepository userRepository,
                                GamePlayerRepository gamePlayerRepository,
                                ActiveWebSocketSessionRegistry activeSessionRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.userRegistry = userRegistry;
        this.userRepository = userRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.activeSessionRegistry = activeSessionRegistry;
    }

    // Kept for focused unit tests that do not need active-session routing.
    public GameWebSocketService(SimpMessagingTemplate messagingTemplate,
                                SimpUserRegistry userRegistry,
                                UserRepository userRepository,
                                GamePlayerRepository gamePlayerRepository) {
        this(messagingTemplate, userRegistry, userRepository, gamePlayerRepository, null);
    }

    // Kept for focused unit tests that only exercise room/game broadcasts.
    public GameWebSocketService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.userRegistry = null;
        this.userRepository = null;
        this.gamePlayerRepository = null;
        this.activeSessionRegistry = null;
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

        timedSend("/topic/rooms/" + roomId, payload, roomId, event);
        Object players = roomState.get("playerCount");
        log.info("[UNO] broadcast roomState roomId={} players={} event={}", roomId, players, event);
    }

    public void broadcastLobbyRoomState(Map<String, Object> roomState, String event, String message) {
        Object roomId = roomState.get("roomId");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "LOBBY_EVENT");
        payload.put("event", event);
        payload.put("roomId", roomId);
        payload.put("roomState", roomState);
        payload.put("timestamp", System.currentTimeMillis());
        if (roomState.get("version") != null) {
            payload.put("version", roomState.get("version"));
        }
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        log.info("[LOBBY] broadcast type={} roomId={}", event, roomId);
        timedSend(LOBBY_TOPIC, payload, roomId, event);
    }

    public void broadcastLobbyRoomRemoved(Long roomId, String event, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "LOBBY_EVENT");
        payload.put("event", event);
        payload.put("roomId", roomId);
        payload.put("timestamp", System.currentTimeMillis());
        if (message != null && !message.isBlank()) {
            payload.put("message", message);
        }

        log.info("[LOBBY] broadcast type={} roomId={}", event, roomId);
        timedSend(LOBBY_TOPIC, payload, roomId, event);
    }

    public void sendLobbyActivityNotification(Map<String, Object> roomState, String event, User actor) {
        if (userRegistry == null || userRepository == null || gamePlayerRepository == null) {
            return;
        }

        Object roomId = roomState.get("roomId");
        Object roomCodeValue = roomState.get("roomCode");
        String roomCode = roomCodeValue != null ? String.valueOf(roomCodeValue) : String.valueOf(roomId);
        String action = "ROOM_CREATED".equals(event) ? "created room" : "joined room";

        Map<String, Object> actorInfo = new LinkedHashMap<>();
        actorInfo.put("userId", actor.getId());
        actorInfo.put("username", actor.getUsername());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "LOBBY_ACTIVITY");
        payload.put("event", event);
        payload.put("roomId", roomId);
        payload.put("roomCode", roomCodeValue);
        payload.put("actor", actorInfo);
        payload.put("actorUserId", actor.getId());
        payload.put("actorUsername", actor.getUsername());
        payload.put("message", actor.getUsername() + " " + action + " " + roomCode);
        payload.put("timestamp", System.currentTimeMillis());

        int recipientCount = 0;
        for (org.springframework.messaging.simp.user.SimpUser connectedUser : userRegistry.getUsers()) {
            User recipient = userRepository.findByUsername(connectedUser.getName()).orElse(null);
            if (recipient == null || gamePlayerRepository.existsByUser(recipient)) {
                continue;
            }
            timedSendToActiveUser(recipient.getUsername(), LOBBY_ACTIVITY_QUEUE, payload, roomId, event);
            recipientCount++;
        }
        log.info("[LOBBY] activity type={} roomId={} actor={} recipients={}",
                event, roomId, actor.getUsername(), recipientCount);
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

        timedSend("/topic/rooms/" + roomId, payload, roomId, "ROOM_DELETED");
        if (gameId != null) {
            timedSend("/topic/games/" + gameId, payload, roomId, "ROOM_DELETED");
        }
        broadcastLobbyRoomRemoved(roomId, "ROOM_REMOVED", message);
        log.info("[WS] ROOM_DELETED -> roomId={}, gameId={}", roomId, gameId);
    }

    public void broadcastPublicGamePatch(PublicGamePatch patch) {
        timedSend("/topic/games/" + patch.gameId(), patch, patch.roomId(), patch.type());
        log.info("[SYNC] action=gameBroadcast roomId={} gameId={} destination=/topic/games/{} currentTurn={} version={}",
                patch.roomId(),
                patch.gameId(),
                patch.gameId(),
                patch.currentPlayerId(),
                patch.version());
    }

    public void sendPrivateHandPatch(String username,
                                     Long roomId,
                                     Long gameId,
                                     Long userId,
                                     PrivateHandPatch patch) {
        String userDestination = "/queue/room/" + roomId + "/hand";
        if (username != null && !username.isBlank()) {
            log.info("[SYNC] action=sendPrivateHandPatch channel=userQueue user={} destination={} version={} patchId={}",
                    username,
                    userDestination,
                    patch.version(),
                    patch.patchId());
            timedSendToActiveUser(username, userDestination, patch, roomId, patch.type());
        } else {
            log.error("[SYNC] action=privateHandPatchMissingPrincipal roomId={} gameId={} userId={} fallbackPollingRequired=true",
                    roomId, gameId, userId);
            Map<String, Object> resyncPayload = new LinkedHashMap<>();
            resyncPayload.put("type", "RESYNC_REQUIRED");
            resyncPayload.put("event", "RESYNC_REQUIRED");
            resyncPayload.put("roomId", roomId);
            resyncPayload.put("gameId", gameId);
            resyncPayload.put("version", patch.version());
            resyncPayload.put("message", "Private state unavailable; refresh snapshot");
            timedSend("/topic/games/" + gameId, resyncPayload, roomId, "RESYNC_REQUIRED");
        }
    }

    public void sendSessionReplaced(String username, String webSocketSessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SESSION_REPLACED");
        payload.put("event", "SESSION_REPLACED");
        payload.put("code", 401);
        payload.put("message", ActiveLoginService.SESSION_REPLACED_MESSAGE);
        payload.put("timestamp", System.currentTimeMillis());
        timedSendToSession(username, webSocketSessionId, "/queue/session-status",
                payload, null, "SESSION_REPLACED");
    }

    private void timedSend(String destination, Object payload, Object roomId, String event) {
        long startedAt = System.nanoTime();
        messagingTemplate.convertAndSend(destination, payload);
        long costMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[PERF] action=websocketBroadcast roomId={} destination={} event={} costMs={}",
                roomId,
                destination,
                event,
                costMs);
        if (payload instanceof PrivateHandPatch patch) {
            log.info("[SYNC] action=privateHandPatch roomId={} gameId={} destination={} userId={} version={} patchId={} handCount={}",
                    roomId,
                    patch.gameId(),
                    destination,
                    patch.userId(),
                    patch.version(),
                    patch.patchId(),
                    patch.handCards() != null ? patch.handCards().size() : 0);
        } else if (payload instanceof PublicGamePatch patch) {
            log.info("[SYNC] action=gameBroadcast roomId={} gameId={} destination={} currentTurn={} version={}",
                    roomId,
                    patch.gameId(),
                    destination,
                    patch.currentPlayerId(),
                    patch.version());
        } else if (payload instanceof Map<?, ?> map) {
            Object roomState = map.get("roomState");
            if (roomState instanceof Map<?, ?> state) {
                log.info("[SYNC] action=roomBroadcast roomId={} destination={} version={}",
                        roomId,
                        destination,
                        state.get("version"));
            }
        }
    }

    private void timedSendToUser(String username, String destination, Object payload, Object roomId, String event) {
        long startedAt = System.nanoTime();
        messagingTemplate.convertAndSendToUser(username, destination, payload);
        long costMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[PERF] action=userWebsocketSend roomId={} destination=/user{} user={} event={} costMs={}",
                roomId,
                destination,
                username,
                event,
                costMs);
    }

    private void timedSendToActiveUser(String username,
                                       String destination,
                                       Object payload,
                                       Object roomId,
                                       String event) {
        if (activeSessionRegistry == null) {
            timedSendToUser(username, destination, payload, roomId, event);
            return;
        }

        for (String sessionId : activeSessionRegistry.findActiveSessionIds(username)) {
            timedSendToSession(username, sessionId, destination, payload, roomId, event);
        }
    }

    private void timedSendToSession(String username,
                                    String sessionId,
                                    String destination,
                                    Object payload,
                                    Object roomId,
                                    String event) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);
        long startedAt = System.nanoTime();
        messagingTemplate.convertAndSendToUser(username, destination, payload, headers.getMessageHeaders());
        long costMs = (System.nanoTime() - startedAt) / 1_000_000;
        log.info("[PERF] action=sessionWebsocketSend roomId={} destination=/user{} user={} sessionId={} event={} costMs={}",
                roomId, destination, username, sessionId, event, costMs);
    }
}
