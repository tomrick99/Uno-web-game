package com.uno.service;

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
import com.uno.websocket.ConnectionPresenceRegistry;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisconnectGracePeriodTest {

    @Test
    void fullyConnectedGameDoesNotEnterWaitingState() {
        Fixture fixture = fixture();

        Map<String, Object> state = fixture.service.getGameState(fixture.game.getId());

        assertNull(state.get("disconnectWait"));
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
        verify(fixture.playerRepository, never()).delete(any(GamePlayer.class));
    }

    @Test
    void realDisconnectStartsSixtySecondWaitAndFreezesTurn() {
        Fixture fixture = fixture();

        fixture.disconnectAlice();

        Map<String, Object> wait = fixture.waitState();
        assertTrue((Boolean) wait.get("active"));
        assertEquals(fixture.alice.getId(), wait.get("disconnectedPlayerId"));
        assertEquals("alice", wait.get("disconnectedPlayerName"));
        assertEquals(60, wait.get("remainingSeconds"));
        assertEquals(60L, wait.get("gracePeriodSeconds"));
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.service.playCard(fixture.game.getId(), fixture.alice.getId(), 0, null));
        assertEquals("Game is waiting for a disconnected player", error.getMessage());
    }

    @Test
    void countdownDecreasesAndReconnectCancelsIt() {
        Fixture fixture = fixture();
        fixture.disconnectAlice();

        fixture.clock.advanceSeconds(31);
        assertEquals(29, fixture.waitState().get("remainingSeconds"));

        fixture.presence.connected("alice", "alice-reconnected");
        fixture.service.handlePlayerReconnected("alice");

        assertNull(fixture.service.getGameState(fixture.game.getId()).get("disconnectWait"));
        assertEquals(3, fixture.players.size());
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
    }

    @Test
    void authenticatedGamePageRejoinCancelsWaitAndRefreshDoesNotRestartCountdown() {
        Fixture fixture = fixture();
        fixture.disconnectAlice();
        long originalDeadline = ((Number) fixture.waitState().get("deadlineEpochMs")).longValue();

        fixture.clock.advanceSeconds(17);
        assertEquals(43, fixture.waitState().get("remainingSeconds"));
        assertEquals(originalDeadline, ((Number) fixture.waitState().get("deadlineEpochMs")).longValue());

        Map<String, Object> reconnectSnapshot = fixture.service.joinGame(
                fixture.room.getId(), fixture.alice.getId());

        assertNull(gameState(reconnectSnapshot).get("disconnectWait"));
        assertFalse(fixture.registry.isWaiting(fixture.room.getId()));
        verify(fixture.wsService, atLeastOnce()).broadcastPublicGamePatch(
                org.mockito.ArgumentMatchers.argThat(patch ->
                        "PLAYER_RECONNECTED".equals(patch.type()) && patch.disconnectWait() == null));

        fixture.clock.advanceSeconds(5);
        Map<String, Object> refreshedSnapshot = fixture.service.joinGame(
                fixture.room.getId(), fixture.alice.getId());

        assertNull(gameState(refreshedSnapshot).get("disconnectWait"));
        assertFalse(fixture.registry.isWaiting(fixture.room.getId()));
    }

    @Test
    void timeoutRemovesPlayerKeepsTurnOrderValidAndGameCanContinue() {
        Fixture fixture = fixture();
        fixture.disconnectAlice();

        fixture.clock.advanceSeconds(59);
        fixture.service.processExpiredDisconnectWaits();
        assertEquals(3, fixture.players.size());

        fixture.clock.advanceSeconds(1);
        fixture.service.processExpiredDisconnectWaits();

        assertEquals(List.of(fixture.bob.getId(), fixture.charlie.getId()),
                fixture.players.stream().map(player -> player.getUser().getId()).toList());
        assertEquals(List.of(0, 1), fixture.players.stream().map(GamePlayer::getSeatIndex).toList());
        assertEquals(fixture.bob.getId(), fixture.game.getCurrentTurn());
        assertEquals(GameStatus.PLAYING, fixture.game.getStatus());
        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        assertNull(fixture.service.getGameState(fixture.game.getId()).get("disconnectWait"));

        fixture.service.drawCard(fixture.game.getId(), fixture.bob.getId());
        assertEquals(fixture.charlie.getId(), fixture.game.getCurrentTurn());
    }

    @Test
    void timeoutPreservesCounterClockwiseTurnOrder() {
        Fixture fixture = fixture();
        fixture.game.setClockwise(false);
        fixture.disconnectAlice();

        fixture.clock.advanceSeconds(60);
        fixture.service.processExpiredDisconnectWaits();

        assertEquals(fixture.charlie.getId(), fixture.game.getCurrentTurn());
        fixture.service.drawCard(fixture.game.getId(), fixture.charlie.getId());
        assertEquals(fixture.bob.getId(), fixture.game.getCurrentTurn());
    }

    @Test
    void continueRequiresEveryConnectedRemainingPlayer() {
        Fixture fixture = fixture();
        fixture.disconnectAlice();

        Map<String, Object> firstVote = fixture.service.continueWithoutDisconnectedPlayer(
                fixture.game.getId(), fixture.bob.getId());

        assertEquals("DISCONNECT_CONTINUE_VOTE", firstVote.get("type"));
        assertEquals(3, fixture.players.size());
        assertTrue(fixture.registry.isWaiting(fixture.room.getId()));
        assertEquals(1L, fixture.waitState().get("approvalCount"));
        assertEquals(2, fixture.waitState().get("requiredCount"));

        Map<String, Object> unanimous = fixture.service.continueWithoutDisconnectedPlayer(
                fixture.game.getId(), fixture.charlie.getId());

        assertEquals("PLAYER_REMOVED", unanimous.get("type"));
        assertEquals("UNANIMOUS_CONTINUE", unanimous.get("reason"));
        assertEquals(2, fixture.players.size());
        assertFalse(fixture.registry.isWaiting(fixture.room.getId()));
        assertEquals(fixture.bob.getId(), fixture.game.getCurrentTurn());
    }

    private Fixture fixture() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));
        DisconnectWaitRegistry registry = new DisconnectWaitRegistry(clock);
        ConnectionPresenceRegistry presence = new ConnectionPresenceRegistry();
        GameService service = new GameService(
                gameRepository,
                playerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService,
                registry,
                presence);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User charlie = user(3L, "charlie");
        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("ROOM10");
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(3);
        room.setGameMode(GameMode.CLASSIC);
        room.setCreatedAt(LocalDateTime.now());

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(alice.getId());
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson("[{\"color\":\"BLUE\",\"type\":\"NUMBER\",\"value\":4}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":2}]");
        game.setCreatedAt(LocalDateTime.now());

        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);
        GamePlayer charliePlayer = player(game, charlie, 2);
        List<GamePlayer> players = new ArrayList<>(List.of(alicePlayer, bobPlayer, charliePlayer));

        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenAnswer(ignored -> new ArrayList<>(players));
        when(playerRepository.findByUser(alice)).thenReturn(List.of(alicePlayer));
        when(playerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(playerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(bobPlayer));
        when(playerRepository.findByGameAndUser(game, charlie)).thenReturn(Optional.of(charliePlayer));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(userRepository.getReferenceById(alice.getId())).thenReturn(alice);
        when(userRepository.getReferenceById(bob.getId())).thenReturn(bob);
        when(userRepository.getReferenceById(charlie.getId())).thenReturn(charlie);
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doAnswer(invocation -> {
            players.remove(invocation.getArgument(0));
            return null;
        }).when(playerRepository).delete(any(GamePlayer.class));
        when(roomService.getRoomState(room)).thenAnswer(ignored -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("roomId", room.getId());
            state.put("playerCount", players.size());
            return state;
        });
        when(roomService.getRoomState(room.getId())).thenAnswer(ignored -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("roomId", room.getId());
            state.put("playerCount", players.size());
            return state;
        });

        presence.connected("alice", "alice-session");
        presence.connected("bob", "bob-session");
        presence.connected("charlie", "charlie-session");
        return new Fixture(service, registry, presence, clock, playerRepository, room, game,
                wsService, alice, bob, charlie, players);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> gameState(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("gameState");
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static GamePlayer player(Game game, User user, int seat) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seat);
        player.setHandCards(new ArrayList<>(List.of(new Card(CardColor.RED, com.uno.entity.enums.CardType.NUMBER, seat + 3))));
        return player;
    }

    private record Fixture(GameService service,
                           DisconnectWaitRegistry registry,
                           ConnectionPresenceRegistry presence,
                           MutableClock clock,
                           GamePlayerRepository playerRepository,
                           Room room,
                           Game game,
                           GameWebSocketService wsService,
                           User alice,
                           User bob,
                           User charlie,
                           List<GamePlayer> players) {

        void disconnectAlice() {
            assertTrue(presence.disconnected("alice", "alice-session"));
            service.handlePlayerDisconnected("alice");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> waitState() {
            return (Map<String, Object>) service.getGameState(game.getId()).get("disconnectWait");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
