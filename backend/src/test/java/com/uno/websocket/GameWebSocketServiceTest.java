package com.uno.websocket;

import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.dto.realtime.PublicPlayerInfo;
import com.uno.entity.User;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameWebSocketServiceTest {

    @Test
    void sessionReplacementNoticeIsExplicitAndTargetsOnlyTheOldWebSocketSession() {
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService service = new GameWebSocketService(template);

        service.sendSessionReplaced("alice", "old-ws-session");

        assertEquals("alice", template.user);
        assertEquals("/queue/session-control", template.userDestination);
        Map<?, ?> payload = (Map<?, ?>) template.userPayload;
        assertEquals("SESSION_REPLACED", payload.get("type"));
        assertEquals("LOGIN_REPLACED", payload.get("reason"));
        assertEquals("This account was signed in on another device. You have been signed out.",
                payload.get("message"));
        assertEquals("old-ws-session",
                template.userHeaders.get(SimpMessageHeaderAccessor.SESSION_ID_HEADER));
    }

    @Test
    void roomCreatedActivityIsSentOnlyToConnectedUsersOutsideRooms() {
        RecordingTemplate template = new RecordingTemplate();
        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        UserRepository userRepository = mock(UserRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        GameWebSocketService service = new GameWebSocketService(
                template, registry, userRepository, gamePlayerRepository);

        User alice = user(1L, "alice");
        User tom = user(2L, "tom");
        User bob = user(3L, "bob");
        SimpUser connectedAlice = connectedUser("alice");
        SimpUser connectedTom = connectedUser("tom");
        SimpUser connectedBob = connectedUser("bob");
        when(registry.getUsers()).thenReturn(Set.of(connectedAlice, connectedTom, connectedBob));
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(alice));
        when(userRepository.findByUsername("tom")).thenReturn(java.util.Optional.of(tom));
        when(userRepository.findByUsername("bob")).thenReturn(java.util.Optional.of(bob));
        when(gamePlayerRepository.existsByUser(bob)).thenReturn(true);

        service.sendLobbyActivityNotification(roomState(12L, "ROOM-A"), "ROOM_CREATED", bob);

        assertEquals(Set.of("alice", "tom"), Set.copyOf(template.users));
        assertFalse(template.users.contains("bob"));
        assertTrue(template.userDestinations.stream().allMatch("/queue/lobby-activity"::equals));
        Map<?, ?> payload = (Map<?, ?>) template.userPayloads.get(0);
        assertEquals("LOBBY_ACTIVITY", payload.get("type"));
        assertEquals("ROOM_CREATED", payload.get("event"));
        assertEquals("ROOM-A", payload.get("roomCode"));
        assertEquals("bob", payload.get("actorUsername"));
        assertEquals("bob created room ROOM-A", payload.get("message"));
    }

    @Test
    void playerJoinedActivityExcludesEveryConnectedRoomParticipant() {
        RecordingTemplate template = new RecordingTemplate();
        SimpUserRegistry registry = mock(SimpUserRegistry.class);
        UserRepository userRepository = mock(UserRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        GameWebSocketService service = new GameWebSocketService(
                template, registry, userRepository, gamePlayerRepository);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User charlie = user(3L, "charlie");
        SimpUser connectedAlice = connectedUser("alice");
        SimpUser connectedBob = connectedUser("bob");
        SimpUser connectedCharlie = connectedUser("charlie");
        when(registry.getUsers()).thenReturn(Set.of(connectedAlice, connectedBob, connectedCharlie));
        when(userRepository.findByUsername("alice")).thenReturn(java.util.Optional.of(alice));
        when(userRepository.findByUsername("bob")).thenReturn(java.util.Optional.of(bob));
        when(userRepository.findByUsername("charlie")).thenReturn(java.util.Optional.of(charlie));
        when(gamePlayerRepository.existsByUser(bob)).thenReturn(true);
        when(gamePlayerRepository.existsByUser(charlie)).thenReturn(true);

        service.sendLobbyActivityNotification(roomState(12L, "ROOM-A"), "PLAYER_JOINED", charlie);

        assertEquals(List.of("alice"), template.users);
        Map<?, ?> payload = (Map<?, ?>) template.userPayloads.get(0);
        assertEquals("PLAYER_JOINED", payload.get("event"));
        assertEquals(12L, payload.get("roomId"));
        assertEquals("charlie", payload.get("actorUsername"));
        assertEquals("charlie joined room ROOM-A", payload.get("message"));
    }

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
                false,
                null,
                null,
                null,
                0L,
                List.of(),
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

    private static User user(Long id, String username) {
        User user = new User(username, "pw");
        user.setId(id);
        return user;
    }

    private static SimpUser connectedUser(String username) {
        SimpUser user = mock(SimpUser.class);
        when(user.getName()).thenReturn(username);
        return user;
    }

    private static Map<String, Object> roomState(Long roomId, String roomCode) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("roomId", roomId);
        state.put("roomCode", roomCode);
        return state;
    }

    private static final class RecordingTemplate extends SimpMessagingTemplate {
        private String destination;
        private Object payload;
        private String user;
        private String userDestination;
        private Object userPayload;
        private MessageHeaders userHeaders;
        private final List<String> users = new ArrayList<>();
        private final List<String> userDestinations = new ArrayList<>();
        private final List<Object> userPayloads = new ArrayList<>();

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
            this.users.add(user);
            this.userDestinations.add(destination);
            this.userPayloads.add(payload);
        }

        @Override
        public void convertAndSendToUser(String user,
                                         String destination,
                                         Object payload,
                                         Map<String, Object> headers) {
            this.user = user;
            this.userDestination = destination;
            this.userPayload = payload;
            this.userHeaders = new MessageHeaders(headers);
            this.users.add(user);
            this.userDestinations.add(destination);
            this.userPayloads.add(payload);
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
