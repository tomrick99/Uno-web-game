package com.uno.service;

import com.uno.dto.realtime.PrivateHandPatch;
import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.PendingDrawType;
import com.uno.entity.enums.RoomStatus;
import com.uno.model.Card;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceSnapshotVersionTest {

    @Test
    void snapshotIncludesRoomStateGameStateHandCardsAndVersion() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        Room room = buildRoom(11L, "ROOM11", RoomStatus.PLAYING);
        Game game = buildGame(22L, room, GameStatus.PLAYING, 33L);
        User user = buildUser(33L, "alice");
        room.setHost(user);

        GamePlayer player = buildPlayer(game, user, 0, List.of(
                new Card(CardColor.RED, com.uno.entity.enums.CardType.NUMBER, 5)
        ));

        when(roomRepository.findById(11L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gameRepository.findById(22L)).thenReturn(Optional.of(game));
        when(userRepository.getReferenceById(33L)).thenReturn(user);
        when(gamePlayerRepository.findByGameAndUser(game, user)).thenReturn(Optional.of(player));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(player));

        Map<String, Object> snapshot = gameService.getRealtimeSnapshotByRoomId(11L, 33L);

        assertEquals("FULL_SNAPSHOT", snapshot.get("type"));
        assertNotNull(snapshot.get("version"));
        assertInstanceOf(List.class, snapshot.get("handCards"));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotRoomState = (Map<String, Object>) snapshot.get("roomState");
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshotGameState = (Map<String, Object>) snapshot.get("gameState");
        assertEquals(snapshot.get("version"), snapshotRoomState.get("version"));
        assertEquals(snapshot.get("version"), snapshotGameState.get("version"));
        assertEquals(snapshot.get("version"), snapshot.get("roomVersion"));
        assertEquals(snapshot.get("version"), snapshot.get("gameVersion"));
        assertEquals(snapshot.get("version"), snapshot.get("handVersion"));
    }

    @Test
    void playCardBroadcastsVersionAndAdvancesCurrentPlayer() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = buildUser(1L, "alice");
        User bob = buildUser(2L, "bob");
        Room room = buildRoom(10L, "ROOM10", RoomStatus.PLAYING);
        room.setHost(alice);

        Game game = buildGame(20L, room, GameStatus.PLAYING, 1L);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setPendingDrawCount(0);
        game.setCurrentColor(CardColor.RED);
        game.setDrawPileJson("[]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alicePlayer = buildPlayer(game, alice, 0, List.of(
                new Card(CardColor.RED, com.uno.entity.enums.CardType.NUMBER, 5),
                new Card(CardColor.BLUE, com.uno.entity.enums.CardType.NUMBER, 1)
        ));
        GamePlayer bobPlayer = buildPlayer(game, bob, 1, List.of(
                new Card(CardColor.GREEN, com.uno.entity.enums.CardType.NUMBER, 7)
        ));
        List<GamePlayer> players = new ArrayList<>(List.of(alicePlayer, bobPlayer));

        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(userRepository.getReferenceById(1L)).thenReturn(alice);
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> ack = gameService.playCard(20L, 1L, 0, null);

        assertEquals("CARD_PLAYED", ack.get("type"));
        assertTrue(Boolean.TRUE.equals(ack.get("accepted")));
        assertNotNull(ack.get("version"));

        assertInstanceOf(PublicGamePatch.class, template.lastPublicPayload);
        PublicGamePatch publicPatch = (PublicGamePatch) template.lastPublicPayload;
        assertEquals("CARD_PLAYED", publicPatch.type());
        assertEquals(2L, publicPatch.currentPlayerId());
        assertEquals(ack.get("version"), publicPatch.version());
        assertEquals(1, publicPatch.players().get(0).handCount());

        assertInstanceOf(PrivateHandPatch.class, template.lastUserPayload);
        PrivateHandPatch privatePatch = (PrivateHandPatch) template.lastUserPayload;
        assertEquals(ack.get("version"), privatePatch.version());
        assertEquals(1, privatePatch.handCards().size());
        assertNotNull(privatePatch.patchId());
        assertEquals(2L, game.getCurrentTurn());
        assertEquals("alice", template.lastUser);
        assertEquals("/queue/room/10/hand", template.lastUserDestination);
    }

    @Test
    void drawCardBroadcastKeepsDrawnCardOutOfPublicPatch() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = buildUser(1L, "alice");
        User bob = buildUser(2L, "bob");
        Room room = buildRoom(12L, "ROOM12", RoomStatus.PLAYING);
        room.setHost(alice);

        Game game = buildGame(24L, room, GameStatus.PLAYING, 1L);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setPendingDrawCount(0);
        game.setCurrentColor(CardColor.RED);
        game.setDrawPileJson("[{\"color\":\"GREEN\",\"type\":\"NUMBER\",\"value\":8}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alicePlayer = buildPlayer(game, alice, 0, List.of(
                new Card(CardColor.BLUE, com.uno.entity.enums.CardType.NUMBER, 1)
        ));
        GamePlayer bobPlayer = buildPlayer(game, bob, 1, List.of(
                new Card(CardColor.GREEN, com.uno.entity.enums.CardType.NUMBER, 7)
        ));
        List<GamePlayer> players = new ArrayList<>(List.of(alicePlayer, bobPlayer));

        when(gameRepository.findById(24L)).thenReturn(Optional.of(game));
        when(userRepository.getReferenceById(1L)).thenReturn(alice);
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> ack = gameService.drawCard(24L, 1L);

        assertEquals("CARD_DRAWN_PUBLIC", ack.get("type"));
        assertInstanceOf(PublicGamePatch.class, template.lastPublicPayload);
        PublicGamePatch publicPatch = (PublicGamePatch) template.lastPublicPayload;
        assertEquals("CARD_DRAWN_PUBLIC", publicPatch.type());
        assertEquals(2L, publicPatch.currentPlayerId());
        assertEquals(2, publicPatch.players().get(0).handCount());

        assertInstanceOf(PrivateHandPatch.class, template.lastUserPayload);
        PrivateHandPatch privatePatch = (PrivateHandPatch) template.lastUserPayload;
        assertEquals(2, privatePatch.handCards().size());
        assertNotNull(privatePatch.patchId());
    }

    @Test
    void joinGameBroadcastsLobbyEventWhenPlayerJoinsWaitingRoom() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = buildUser(1L, "alice");
        User bob = buildUser(2L, "bob");
        Room room = buildRoom(30L, "ROOM30", RoomStatus.WAITING);
        room.setHost(alice);
        room.setMaxPlayers(3);
        Game game = buildGame(40L, room, GameStatus.WAITING, null);
        GamePlayer alicePlayer = buildPlayer(game, alice, 0, List.of());
        GamePlayer bobPlayer = buildPlayer(game, bob, 1, List.of());

        when(roomRepository.findById(30L)).thenReturn(Optional.of(room));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(userRepository.getReferenceById(2L)).thenReturn(bob);
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gameRepository.findById(40L)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.findByGameAndUser(game, bob))
                .thenReturn(Optional.empty(), Optional.of(bobPlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game))
                .thenReturn(
                        List.of(alicePlayer),
                        List.of(alicePlayer, bobPlayer),
                        List.of(alicePlayer, bobPlayer),
                        List.of(alicePlayer, bobPlayer),
                        List.of(alicePlayer, bobPlayer)
                );
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long versionBeforeJoin = gameService.getCurrentStateVersion(30L, game);
        gameService.joinGame(30L, 2L);

        assertTrue(template.sentDestinations.contains("/topic/lobby"));
        Map<?, ?> lobbyPayload = template.lastPayloadFor("/topic/lobby");
        assertEquals("LOBBY_EVENT", lobbyPayload.get("type"));
        assertEquals("PLAYER_JOINED", lobbyPayload.get("event"));
        assertEquals(30L, lobbyPayload.get("roomId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> roomState = (Map<String, Object>) lobbyPayload.get("roomState");
        assertEquals(2, roomState.get("playerCount"));
        assertTrue(((Number) roomState.get("version")).longValue() > versionBeforeJoin);
    }

    @Test
    void leaveWaitingRoomBroadcastsLobbyEventWhenPlayerCountChanges() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = buildUser(1L, "alice");
        User bob = buildUser(2L, "bob");
        Room room = buildRoom(31L, "ROOM31", RoomStatus.WAITING);
        room.setHost(alice);
        Game game = buildGame(41L, room, GameStatus.WAITING, null);
        GamePlayer alicePlayer = buildPlayer(game, alice, 0, List.of());
        GamePlayer bobPlayer = buildPlayer(game, bob, 1, List.of());

        when(roomRepository.findById(31L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game))
                .thenReturn(List.of(bobPlayer), List.of(bobPlayer));
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        long versionBeforeLeave = gameService.getCurrentStateVersion(31L, game);
        gameService.leaveRoom(31L, 1L);

        assertTrue(template.sentDestinations.contains("/topic/lobby"));
        Map<?, ?> lobbyPayload = template.lastPayloadFor("/topic/lobby");
        assertEquals("LOBBY_EVENT", lobbyPayload.get("type"));
        assertEquals("PLAYER_LEFT", lobbyPayload.get("event"));
        assertEquals(31L, lobbyPayload.get("roomId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> roomState = (Map<String, Object>) lobbyPayload.get("roomState");
        assertEquals(1, roomState.get("playerCount"));
        assertTrue(((Number) roomState.get("version")).longValue() > versionBeforeLeave);
    }

    @Test
    void leavingFinishedGameDeletesRoomAndRemovesLobbyCard() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);
        GameService gameService = new GameService(gameRepository, gamePlayerRepository, roomRepository, userRepository, wsService, roomService);

        User alice = buildUser(1L, "alice");
        User bob = buildUser(2L, "bob");
        Room room = buildRoom(33L, "ROOM33", RoomStatus.CLOSED);
        room.setHost(alice);
        Game game = buildGame(43L, room, GameStatus.FINISHED, null);
        GamePlayer alicePlayer = buildPlayer(game, alice, 0, List.of());
        GamePlayer bobPlayer = buildPlayer(game, bob, 1, List.of());

        when(roomRepository.findById(33L)).thenReturn(Optional.of(room));
        when(userRepository.findById(1L)).thenReturn(Optional.of(alice));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(bobPlayer));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        gameService.leaveRoom(33L, 1L);

        verify(gamePlayerRepository).deleteAllByGame(game);
        verify(gameRepository).delete(game);
        verify(roomRepository).delete(room);
        Map<?, ?> lobbyPayload = template.lastPayloadFor("/topic/lobby");
        assertEquals("LOBBY_EVENT", lobbyPayload.get("type"));
        assertEquals("ROOM_REMOVED", lobbyPayload.get("event"));
        assertEquals(33L, lobbyPayload.get("roomId"));
    }

    @Test
    void adminDeleteBroadcastsLobbyRoomRemoved() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        RecordingTemplate template = new RecordingTemplate();
        GameWebSocketService wsService = new GameWebSocketService(template);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User admin = buildUser(1L, "admin");
        Room room = buildRoom(32L, "ROOM32", RoomStatus.WAITING);
        room.setHost(admin);
        Game game = buildGame(42L, room, GameStatus.WAITING, null);

        when(roomRepository.findById(32L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));

        gameService.deleteRoomByAdmin(32L, "admin");

        assertTrue(template.sentDestinations.contains("/topic/lobby"));
        Map<?, ?> lobbyPayload = template.lastPayloadFor("/topic/lobby");
        assertEquals("LOBBY_EVENT", lobbyPayload.get("type"));
        assertEquals("ROOM_REMOVED", lobbyPayload.get("event"));
        assertEquals(32L, lobbyPayload.get("roomId"));
    }

    private Room buildRoom(Long roomId, String roomCode, RoomStatus status) {
        Room room = new Room();
        room.setId(roomId);
        room.setRoomCode(roomCode);
        room.setStatus(status);
        room.setMaxPlayers(2);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        room.setCreatedAt(LocalDateTime.now());
        return room;
    }

    private Game buildGame(Long gameId, Room room, GameStatus status, Long currentTurn) {
        Game game = new Game();
        game.setId(gameId);
        game.setRoom(room);
        game.setStatus(status);
        game.setCurrentTurn(currentTurn);
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setCreatedAt(LocalDateTime.now());
        game.setDrawPileJson("[]");
        game.setDiscardPileJson("[]");
        return game;
    }

    private User buildUser(Long userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        return user;
    }

    private GamePlayer buildPlayer(Game game, User user, int seatIndex, List<Card> handCards) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>(handCards));
        return player;
    }

    private static final class RecordingTemplate extends SimpMessagingTemplate {
        private Object lastPublicPayload;
        private Object lastUserPayload;
        private String lastUser;
        private String lastUserDestination;
        private final List<String> sentDestinations = new ArrayList<>();
        private final List<Object> sentPayloads = new ArrayList<>();

        private RecordingTemplate() {
            super(new NoOpMessageChannel());
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            sentDestinations.add(destination);
            sentPayloads.add(payload);
            if (destination.startsWith("/topic/games/") && !destination.contains("/hands/")) {
                this.lastPublicPayload = payload;
            }
        }

        @Override
        public void convertAndSendToUser(String user, String destination, Object payload) {
            this.lastUser = user;
            this.lastUserDestination = destination;
            this.lastUserPayload = payload;
        }

        private Map<?, ?> lastPayloadFor(String destination) {
            int index = IntStream.range(0, sentDestinations.size())
                    .filter(i -> destination.equals(sentDestinations.get(i)))
                    .reduce((first, second) -> second)
                    .orElseThrow();
            return (Map<?, ?>) sentPayloads.get(index);
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
