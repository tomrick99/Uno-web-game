package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.GameStatus;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceAdminUpdateTest {

    @Test
    void reducingMaxPlayersToCurrentCountStartsWaitingGameOnce() {
        Fixture fixture = fixture(4);

        Map<String, Object> roomState = fixture.gameService.updateRoomConfigByAdmin(
                fixture.room.getId(),
                2,
                16,
                15,
                GameMode.NO_MERCY,
                99L,
                "admin"
        );

        assertEquals(2, fixture.room.getMaxPlayers());
        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        assertEquals(GameStatus.PLAYING, fixture.game.getStatus());
        assertEquals("PLAYING", roomState.get("status"));
        assertEquals("PLAYING", roomState.get("gameStatus"));
        assertEquals(2, roomState.get("playerCount"));
        assertNotNull(fixture.game.getCurrentTurn());
        assertEquals(7, fixture.alicePlayer.getHandCards().size());
        assertEquals(7, fixture.bobPlayer.getHandCards().size());

        verify(fixture.wsService, times(1)).broadcastRoomState(any(), eq("GAME_STARTED"), eq("Game started"));
        verify(fixture.wsService, times(1)).broadcastLobbyRoomState(any(), eq("ROOM_UPDATED"), eq("Game started"));
        verify(fixture.wsService, times(1)).broadcastPublicGamePatch(any(PublicGamePatch.class));
        verify(fixture.wsService, times(2)).sendPrivateHandPatch(any(), any(), any(), any(), any());

        assertThrows(IllegalArgumentException.class, () -> fixture.gameService.updateRoomConfigByAdmin(
                fixture.room.getId(),
                2,
                16,
                15,
                GameMode.NO_MERCY,
                99L,
                "admin"
        ));
        verify(fixture.wsService, times(1)).broadcastPublicGamePatch(any(PublicGamePatch.class));
    }

    @Test
    void updatingWaitingRoomBelowCapacityDoesNotStartGame() {
        Fixture fixture = fixture(4);

        Map<String, Object> roomState = fixture.gameService.updateRoomConfigByAdmin(
                fixture.room.getId(),
                3,
                16,
                15,
                GameMode.CLASSIC,
                99L,
                "admin"
        );

        assertEquals(RoomStatus.WAITING, fixture.room.getStatus());
        assertEquals(GameStatus.WAITING, fixture.game.getStatus());
        assertEquals("WAITING", roomState.get("status"));
        verify(fixture.wsService).broadcastRoomState(any(), eq("ROOM_UPDATED"), eq("Room updated"));
        verify(fixture.wsService, never()).broadcastPublicGamePatch(any(PublicGamePatch.class));
    }

    @Test
    void joiningLastSeatStillStartsGame() {
        Fixture fixture = fixture(2);
        User joiningUser = fixture.bobPlayer.getUser();

        when(fixture.userRepository.findById(joiningUser.getId())).thenReturn(Optional.of(joiningUser));
        when(fixture.userRepository.getReferenceById(joiningUser.getId())).thenReturn(joiningUser);
        when(fixture.gamePlayerRepository.findByGameAndUser(fixture.game, joiningUser))
                .thenReturn(Optional.empty(), Optional.of(fixture.bobPlayer));
        when(fixture.gamePlayerRepository.findByGameOrderBySeatIndexAsc(fixture.game))
                .thenReturn(
                        List.of(fixture.alicePlayer),
                        List.of(fixture.alicePlayer, fixture.bobPlayer)
                );

        fixture.gameService.joinGame(fixture.room.getId(), joiningUser.getId());

        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        assertEquals(GameStatus.PLAYING, fixture.game.getStatus());
        verify(fixture.wsService, times(1)).broadcastPublicGamePatch(any(PublicGamePatch.class));
    }

    private Fixture fixture(int maxPlayers) {
        RoomRepository roomRepository = mock(RoomRepository.class);
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);
        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService
        );

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Room room = new Room();
        room.setId(10L);
        room.setRoomCode("ROOM10");
        room.setHost(alice);
        room.setStatus(RoomStatus.WAITING);
        room.setMaxPlayers(maxPlayers);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        room.setCreatedAt(LocalDateTime.now());

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        game.setCreatedAt(LocalDateTime.now());

        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);
        List<GamePlayer> players = List.of(alicePlayer, bobPlayer);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gameRepository.findById(game.getId())).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        return new Fixture(
                roomRepository,
                gameRepository,
                gamePlayerRepository,
                userRepository,
                wsService,
                gameService,
                room,
                game,
                alicePlayer,
                bobPlayer
        );
    }

    private User user(Long id, String username) {
        User user = new User(username, "pw");
        user.setId(id);
        return user;
    }

    private GamePlayer player(Game game, User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>());
        return player;
    }

    private record Fixture(RoomRepository roomRepository,
                           GameRepository gameRepository,
                           GamePlayerRepository gamePlayerRepository,
                           UserRepository userRepository,
                           GameWebSocketService wsService,
                           GameService gameService,
                           Room room,
                           Game game,
                           GamePlayer alicePlayer,
                           GamePlayer bobPlayer) {
    }
}
