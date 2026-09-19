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
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceOfflineTimeoutTest {

    @Test
    void timedOutCurrentPlayerAdvancesClockwiseAndGameContinues() {
        Fixture fixture = fixture(true);

        fixture.service.handlePlayerOfflineTimeout("bob");

        PublicGamePatch patch = capturePatch(fixture.wsService);
        assertEquals(fixture.carol.getId(), fixture.game.getCurrentTurn());
        assertEquals(fixture.carol.getId(), patch.currentPlayerId());
        assertEquals(1, patch.currentPlayerIndex());
        assertEquals(1, patch.direction());
        assertEquals(GameStatus.PLAYING.name(), patch.gameStatus());
        assertEquals(List.of(fixture.alice.getId(), fixture.carol.getId(), fixture.dave.getId()),
                patch.players().stream().map(player -> player.userId()).toList());
        assertEquals(List.of(0, 1, 2),
                patch.players().stream().map(player -> player.seatIndex()).toList());
        verifyConsistentBroadcasts(fixture.wsService);
    }

    @Test
    void timedOutCurrentPlayerAdvancesInReverseDirection() {
        Fixture fixture = fixture(false);

        fixture.service.handlePlayerOfflineTimeout("bob");

        PublicGamePatch patch = capturePatch(fixture.wsService);
        assertEquals(fixture.alice.getId(), fixture.game.getCurrentTurn());
        assertEquals(fixture.alice.getId(), patch.currentPlayerId());
        assertEquals(0, patch.currentPlayerIndex());
        assertEquals(-1, patch.direction());
        assertTrue(patch.players().get(0).currentPlayer());
        verifyConsistentBroadcasts(fixture.wsService);
    }

    private PublicGamePatch capturePatch(GameWebSocketService wsService) {
        ArgumentCaptor<PublicGamePatch> captor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(wsService).broadcastPublicGamePatch(captor.capture());
        PublicGamePatch patch = captor.getValue();
        assertEquals("PLAYER_TIMEOUT", patch.type());
        assertEquals(3, patch.players().size());
        return patch;
    }

    private void verifyConsistentBroadcasts(GameWebSocketService wsService) {
        verify(wsService).broadcastRoomState(anyMap(), eq("PLAYER_TIMEOUT"), eq("bob timed out"));
        verify(wsService, times(3)).sendPrivateHandPatch(any(), any(), any(), any(), any());
    }

    private Fixture fixture(boolean clockwise) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = new RoomService(roomRepository, gameRepository, playerRepository);
        GameService service = new GameService(
                gameRepository,
                playerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User carol = user(3L, "carol");
        User dave = user(4L, "dave");

        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("ROOM10");
        room.setHost(alice);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(4);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        room.setCreatedAt(LocalDateTime.now());

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(bob.getId());
        game.setClockwise(clockwise);
        game.setCurrentColor(CardColor.RED);
        game.setPendingDrawCount(0);
        game.setPendingDrawType(PendingDrawType.NONE);
        game.setDrawPileJson("[]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");
        game.setCreatedAt(LocalDateTime.now());

        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);
        GamePlayer carolPlayer = player(game, carol, 2);
        GamePlayer davePlayer = player(game, dave, 3);
        List<GamePlayer> beforeTimeout = List.of(alicePlayer, bobPlayer, carolPlayer, davePlayer);
        List<GamePlayer> afterTimeout = new ArrayList<>(List.of(alicePlayer, carolPlayer, davePlayer));

        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(playerRepository.findByUser(bob)).thenReturn(List.of(bobPlayer));
        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(playerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(bobPlayer));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game))
                .thenReturn(beforeTimeout, afterTimeout, afterTimeout);
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        return new Fixture(service, wsService, game, alice, carol, dave);
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
        player.setHandCards(List.of());
        return player;
    }

    private record Fixture(GameService service,
                           GameWebSocketService wsService,
                           Game game,
                           User alice,
                           User carol,
                           User dave) {
    }
}
