package com.uno.websocket;

import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.dto.realtime.PublicPlayerInfo;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWebSocketServiceTest {

    @Test
    void lobbyRoomStateBroadcastUsesLobbyTopic() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);

        Map<String, Object> roomState = new LinkedHashMap<>();
        roomState.put("roomId", 12L);
        roomState.put("playerCount", 2);
        roomState.put("version", 123L);

        service.broadcastLobbyRoomState(roomState, "ROOM_UPDATED", "updated");

        assertEquals("/topic/lobby", template.destination);
        Map<?, ?> payload = (Map<?, ?>) template.payload;
        assertEquals("LOBBY_EVENT", payload.get("type"));
        assertEquals("ROOM_UPDATED", payload.get("event"));
        assertEquals(12L, payload.get("roomId"));
        assertEquals(123L, payload.get("version"));
        assertTrue(payload.get("timestamp") instanceof Long);
    }

    @Test
    void roomDeletedAlsoBroadcastsLobbyRemovedEvent() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);

        service.broadcastRoomDeleted(12L, 20L, "deleted");

        assertEquals("/topic/lobby", template.destination);
        Map<?, ?> payload = (Map<?, ?>) template.payload;
        assertEquals("LOBBY_EVENT", payload.get("type"));
        assertEquals("ROOM_REMOVED", payload.get("event"));
        assertEquals(12L, payload.get("roomId"));
        assertTrue(payload.get("timestamp") instanceof Long);
    }

    @Test
    void publicGameBroadcastCarriesVersionWithoutHandCards() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);

        PublicGamePatch patch = new PublicGamePatch(
                "CARD_PLAYED",
                8L,
                20L,
                12345L,
                999L,
                1L,
                "alice",
                2L,
                "bob",
                1,
                "RED",
                1,
                Map.of("color", "RED", "type", "NUMBER", "value", 3),
                Map.of("color", "RED", "type", "NUMBER", "value", 3),
                0,
                "NONE",
                null,
                42,
                "PLAYING",
                "PLAYING",
                List.of(new PublicPlayerInfo(1L, "alice", 4, 0, false, false, false)),
                null,
                List.of(),
                "played",
                false
        );

        service.broadcastPublicGamePatch(patch);

        assertEquals("/topic/games/20", template.destination);
        assertInstanceOf(PublicGamePatch.class, template.payload);
        PublicGamePatch sentPatch = (PublicGamePatch) template.payload;
        assertEquals(12345L, sentPatch.version());
        assertEquals("NONE", sentPatch.pendingDrawType());
        assertEquals(42, sentPatch.drawPileSize());
        assertEquals(List.of(), sentPatch.rematchReadyPlayerIds());
        assertFalse(sentPatch.getClass().getRecordComponents()[0].getName().equals("handCards"));
        assertNull(findRecordComponent(sentPatch, "handCards"));
    }

    @Test
    void roomBroadcastStaysSeparateFromGameStateFields() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);

        Map<String, Object> roomState = new LinkedHashMap<>();
        roomState.put("roomId", 15L);
        roomState.put("playerCount", 2);
        roomState.put("version", 555L);
        roomState.put("players", List.of(Map.of("userId", 1L, "username", "alice")));

        service.broadcastRoomState(roomState, "PLAYER_JOINED", "joined");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) template.payload;
        assertFalse(payload.containsKey("gameState"));
        assertFalse(payload.containsKey("handCards"));
        @SuppressWarnings("unchecked")
        Map<String, Object> broadcastRoomState = (Map<String, Object>) payload.get("roomState");
        assertFalse(broadcastRoomState.containsKey("currentTurn"));
        assertFalse(broadcastRoomState.containsKey("currentPlayerId"));
        assertEquals(555L, broadcastRoomState.get("version"));
    }

    @Test
    void privateHandPatchUsesOnlyAuthenticatedUserQueue() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);
        PrivateHandPatch patch = new PrivateHandPatch(
                "HAND_UPDATED",
                8L,
                20L,
                12345L,
                999L,
                99L,
                "8-20-99-12345-HAND_UPDATED",
                List.of(Map.of("color", "RED", "type", "NUMBER", "value", 3)),
                null,
                false,
                0
        );

        service.sendPrivateHandPatch("alice", 8L, 20L, 99L, patch);

        assertEquals("alice", template.user);
        assertEquals("/queue/room/8/hand", template.userDestination);
        assertNull(template.destination);
        assertNull(template.payload);
        assertInstanceOf(PrivateHandPatch.class, template.userPayload);
        assertTrue(((PrivateHandPatch) template.userPayload).handCards().size() == 1);
        assertEquals("8-20-99-12345-HAND_UPDATED", ((PrivateHandPatch) template.userPayload).patchId());
    }

    @Test
    void missingPrincipalRequestsSafeSnapshotInsteadOfPublishingHand() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);
        PrivateHandPatch patch = new PrivateHandPatch(
                "HAND_UPDATED",
                8L,
                20L,
                12345L,
                999L,
                99L,
                "8-20-99-12345-HAND_UPDATED",
                List.of(Map.of("color", "RED", "type", "NUMBER", "value", 3)),
                null,
                false,
                0
        );

        service.sendPrivateHandPatch(null, 8L, 20L, 99L, patch);

        assertNull(template.user);
        assertEquals("/topic/games/20", template.destination);
        Map<?, ?> payload = (Map<?, ?>) template.payload;
        assertEquals("RESYNC_REQUIRED", payload.get("type"));
        assertFalse(payload.containsKey("handCards"));
    }

    private String findRecordComponent(Object record, String name) {
        return java.util.Arrays.stream(record.getClass().getRecordComponents())
                .map(component -> component.getName().equals(name) ? component.getName() : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static final class RecordingTemplate extends SimpMessagingTemplate {
        private String destination;
        private Object payload;
        private String user;
        private String userDestination;
        private Object userPayload;

        private RecordingTemplate() {
            super(new NoOpMessageChannel());
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            this.destination = destination;
            this.payload = payload;
        }

        @Override
        public void convertAndSendToUser(String user, String destination, Object payload) {
            this.user = user;
            this.userDestination = destination;
            this.userPayload = payload;
        }
    }

    private static final class NoOpMessageChannel implements MessageChannel {
        @Override
        public boolean send(Message<?> message) {
            return true;
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return true;
        }
    }
}
