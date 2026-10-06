package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.PendingDrawType;
import com.uno.entity.enums.RoomStatus;
import com.uno.model.Card;
import com.uno.entity.enums.CardType;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceDisconnectGraceTest {

    @Test
    void disconnectionStartsSixtySecondGraceAndPausesTurnActions() {
        Fixture fixture = fixture();
        long disconnectedAt = System.currentTimeMillis();

        fixture.service.playerDisconnected("bob", disconnectedAt);

        assertTrue(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        assertEquals(disconnectedAt + GameService.DISCONNECT_GRACE_PERIOD_MS,
                fixture.service.disconnectGraceDeadline(fixture.room.getId()));
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.service.drawCard(fixture.game.getId(), fixture.alice.getId()));
        assertTrue(error.getMessage().contains("paused"));

        Map<String, Object> state = fixture.service.getGameState(fixture.game.getId());
        assertEquals(true, state.get("disconnectGraceActive"));
        assertEquals(fixture.bob.getId(), state.get("disconnectedPlayerId"));
        assertEquals("bob", state.get("disconnectedPlayerName"));
        verify(fixture.playerRepository, never()).delete(any(GamePlayer.class));
    }

    @Test
    void reconnectDuringGraceResumesWithoutRemovingPlayer() {
        Fixture fixture = fixture();
        fixture.service.playerDisconnected("bob", System.currentTimeMillis());

        fixture.service.playerConnected("bob");

        assertFalse(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        verify(fixture.playerRepository, never()).delete(any(GamePlayer.class));
        assertEquals("PLAYER_RECONNECTED", lastPatch(fixture.wsService).type());
    }

    @Test
    void timeoutRemovesDisconnectedPlayerAndRemainingPlayersCanContinue() {
        Fixture fixture = fixture();
        fixture.game.setCurrentTurn(fixture.bob.getId());
        long disconnectedAt = System.currentTimeMillis();
        fixture.service.playerDisconnected("bob", disconnectedAt);

        fixture.service.expireDisconnectGracePeriods(disconnectedAt + GameService.DISCONNECT_GRACE_PERIOD_MS - 1L);
        assertTrue(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        verify(fixture.playerRepository, never()).delete(fixture.bobPlayer);

        fixture.service.expireDisconnectGracePeriods(disconnectedAt + GameService.DISCONNECT_GRACE_PERIOD_MS);

        assertFalse(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        verify(fixture.playerRepository).delete(fixture.bobPlayer);
        assertEquals(GameStatus.PLAYING, fixture.game.getStatus());
        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        assertEquals(fixture.charlie.getId(), fixture.game.getCurrentTurn());
        PublicGamePatch removalPatch = lastPatch(fixture.wsService);
        assertEquals("PLAYER_REMOVED", removalPatch.type());
        assertEquals(List.of(fixture.alice.getId(), fixture.charlie.getId()),
                removalPatch.players().stream().map(player -> player.userId()).toList());

        fixture.service.drawCard(fixture.game.getId(), fixture.charlie.getId());
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
    }

    @Test
    void continueRequiresEveryOtherOnlinePlayerToApprove() {
        Fixture fixture = fixture();
        fixture.service.playerDisconnected("bob", System.currentTimeMillis());

        fixture.service.continueWithoutDisconnectedPlayer(fixture.game.getId(), fixture.alice.getId());

        assertTrue(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        verify(fixture.playerRepository, never()).delete(fixture.bobPlayer);
        PublicGamePatch votePatch = lastPatch(fixture.wsService);
        assertEquals("CONTINUE_VOTE_UPDATED", votePatch.type());
        assertEquals(List.of(fixture.alice.getId()), votePatch.continueApprovedPlayerIds());
        assertEquals(List.of(fixture.alice.getId(), fixture.charlie.getId()),
                votePatch.eligibleContinuePlayerIds());

        fixture.service.continueWithoutDisconnectedPlayer(fixture.game.getId(), fixture.charlie.getId());

        assertFalse(fixture.service.hasActiveDisconnectGrace(fixture.room.getId()));
        verify(fixture.playerRepository).delete(fixture.bobPlayer);
        assertEquals("PLAYER_REMOVED", lastPatch(fixture.wsService).type());
    }

    private Fixture fixture() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        GameService service = new GameService(
                gameRepository, playerRepository, roomRepository, userRepository, wsService, roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User charlie = user(3L, "charlie");

        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("ROOM10");
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(3);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(alice.getId());
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson("[{\"color\":\"BLUE\",\"type\":\"NUMBER\",\"value\":5}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");
        game.setCreatedAt(LocalDateTime.now());

        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);
        GamePlayer charliePlayer = player(game, charlie, 2);
        List<GamePlayer> players = new ArrayList<>(List.of(alicePlayer, bobPlayer, charliePlayer));

        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(userRepository.getReferenceById(alice.getId())).thenReturn(alice);
        when(userRepository.getReferenceById(charlie.getId())).thenReturn(charlie);
        when(playerRepository.findByUser(bob)).thenReturn(List.of(bobPlayer));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game))
                .thenAnswer(invocation -> new ArrayList<>(players));
        when(playerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(alicePlayer));
        when(playerRepository.findByGameAndUser(game, charlie)).thenReturn(Optional.of(charliePlayer));
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomService.getRoomState(room)).thenAnswer(invocation -> {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("roomId", room.getId());
            state.put("roomCode", room.getRoomCode());
            state.put("status", room.getStatus().name());
            state.put("playerCount", players.size());
            return state;
        });
        doAnswer(invocation -> {
            players.remove(invocation.getArgument(0));
            return null;
        }).when(playerRepository).delete(any(GamePlayer.class));

        service.playerConnected("alice");
        service.playerConnected("bob");
        service.playerConnected("charlie");

        return new Fixture(service, gameRepository, playerRepository, wsService, room, game,
                alice, bob, charlie, alicePlayer, bobPlayer, charliePlayer);
    }

    private PublicGamePatch lastPatch(GameWebSocketService wsService) {
        ArgumentCaptor<PublicGamePatch> captor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(wsService, org.mockito.Mockito.atLeastOnce()).broadcastPublicGamePatch(captor.capture());
        List<PublicGamePatch> patches = captor.getAllValues();
        return patches.get(patches.size() - 1);
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private GamePlayer player(Game game, User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>(List.of(new Card(CardColor.GREEN, CardType.NUMBER, seatIndex + 1))));
        return player;
    }

    private record Fixture(GameService service,
                           GameRepository gameRepository,
                           GamePlayerRepository playerRepository,
                           GameWebSocketService wsService,
                           Room room,
                           Game game,
                           User alice,
                           User bob,
                           User charlie,
                           GamePlayer alicePlayer,
                           GamePlayer bobPlayer,
                           GamePlayer charliePlayer) {
    }
}
