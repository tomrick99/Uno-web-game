package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.DrawPileRule;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.RoomStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomServiceAdminUpdateTest {

    private final RoomRepository roomRepository = mock(RoomRepository.class);
    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
    private final RoomService roomService = new RoomService(roomRepository, gameRepository, gamePlayerRepository);

    @Test
    void waitingRoomCanBeUpdated() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of());

        roomService.updateRoomConfigByAdmin(1L, 6, 16, 15, GameMode.NO_MERCY);

        assertEquals(6, room.getMaxPlayers());
        assertEquals(16, room.getTotalRounds());
        assertEquals(15, room.getRoundTimeLimitMinutes());
        assertEquals(GameMode.NO_MERCY, room.getGameMode());
    }

    @Test
    void noMercyRoomKeepsFiniteDrawPileRule() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of());

        Map<String, Object> state = roomService.updateRoomConfigByAdmin(
                1L, 4, 8, 10, GameMode.NO_MERCY, DrawPileRule.FINITE_DRAW_PILE);

        assertEquals(DrawPileRule.FINITE_DRAW_PILE, room.getDrawPileRule());
        assertEquals(DrawPileRule.FINITE_DRAW_PILE.name(), state.get("drawPileRule"));
    }

    @Test
    void classicRoomAlwaysUsesAutoRefill() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of());

        roomService.updateRoomConfigByAdmin(
                1L, 4, 8, 10, GameMode.CLASSIC, DrawPileRule.FINITE_DRAW_PILE);

        assertEquals(DrawPileRule.AUTO_REFILL, room.getDrawPileRule());
    }

    @Test
    void updateRejectsMaxPlayersBelowCurrentPlayerCount() {
        Room room = room(1L, RoomStatus.WAITING, 4);
        Game game = new Game();
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(new GamePlayer(), new GamePlayer(), new GamePlayer()));

        assertThrows(IllegalArgumentException.class,
                () -> roomService.updateRoomConfigByAdmin(1L, 2, 8, 10, GameMode.CLASSIC));
    }

    @Test
    void playingRoomCannotBeUpdated() {
        Room room = room(1L, RoomStatus.PLAYING, 4);
        when(roomRepository.findById(1L)).thenReturn(Optional.of(room));

        assertThrows(IllegalArgumentException.class,
                () -> roomService.updateRoomConfigByAdmin(1L, 4, 16, 10, GameMode.NO_MERCY));
    }

    @Test
    void waitingRoomStatesUseActualGamePlayerCount() {
        Room room = room(1L, RoomStatus.WAITING, 2);
        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        GamePlayer alice = player(1L, "alice", game, 0);
        GamePlayer bob = player(2L, "bob", game, 1);

        when(roomRepository.findByStatus(RoomStatus.WAITING)).thenReturn(List.of(room));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(alice, bob));

        List<Map<String, Object>> states = roomService.getWaitingRoomStates();

        assertEquals(1, states.size());
        assertEquals(2, states.get(0).get("playerCount"));
    }

    @Test
    void reducingMaxPlayersToCurrentCountStartsWaitingGameExactlyOnce() {
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);

        Room room = room(1L, RoomStatus.WAITING, 4);
        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.WAITING);
        game.setClockwise(true);
        GamePlayer alice = player(1L, "alice", game, 0);
        GamePlayer bob = player(2L, "bob", game, 1);
        room.setHost(alice.getUser());
        List<GamePlayer> players = List.of(alice, bob);

        when(roomRepository.findById(room.getId())).thenReturn(Optional.of(room));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> state = gameService.updateRoomConfigByAdmin(
                room.getId(), 2, 8, 10, GameMode.CLASSIC, 99L, "admin");

        assertEquals(2, room.getMaxPlayers());
        assertEquals(RoomStatus.PLAYING, room.getStatus());
        assertEquals(GameStatus.PLAYING, game.getStatus());
        assertEquals(alice.getUser().getId(), game.getCurrentTurn());
        assertEquals(List.of(7, 7), players.stream().map(player -> player.getHandCards().size()).toList());
        assertEquals(RoomStatus.PLAYING.name(), state.get("status"));
        assertEquals(GameStatus.PLAYING.name(), state.get("gameStatus"));

        verify(wsService, times(1)).broadcastRoomState(
                argThat(roomState -> RoomStatus.PLAYING.name().equals(roomState.get("status"))),
                eq("GAME_STARTED"),
                eq("Game started"));
        verify(wsService, times(1)).broadcastLobbyRoomState(any(), eq("ROOM_UPDATED"), eq("Game started"));
        verify(wsService, times(1)).broadcastPublicGamePatch(
                argThat(patch -> "GAME_STARTED".equals(patch.type()) && patch.currentPlayerId().equals(alice.getUser().getId())));
        verify(wsService, times(2)).sendPrivateHandPatch(any(), eq(room.getId()), eq(game.getId()), any(), any());

        assertThrows(IllegalArgumentException.class, () -> gameService.updateRoomConfigByAdmin(
                room.getId(), 2, 8, 10, GameMode.CLASSIC, 99L, "admin"));
        verify(wsService, times(1)).broadcastPublicGamePatch(any());
        verify(wsService, times(2)).sendPrivateHandPatch(any(), eq(room.getId()), eq(game.getId()), any(), any());
    }

    private Room room(Long id, RoomStatus status, int maxPlayers) {
        User host = new User("admin", "pw");
        host.setId(10L);
        Room room = new Room();
        room.setId(id);
        room.setRoomCode("ABC123");
        room.setHost(host);
        room.setStatus(status);
        room.setMaxPlayers(maxPlayers);
        room.setTotalRounds(8);
        room.setRoundTimeLimitMinutes(10);
        room.setGameMode(GameMode.CLASSIC);
        return room;
    }

    private GamePlayer player(Long userId, String username, Game game, int seatIndex) {
        User user = new User(username, "pw");
        user.setId(userId);
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(List.of());
        return player;
    }
}
