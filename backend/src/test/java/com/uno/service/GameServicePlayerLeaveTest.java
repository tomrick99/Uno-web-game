package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameStatus;
import com.uno.entity.enums.RoomStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.RoomRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServicePlayerLeaveTest {

    @Test
    void currentPlayerLeavingClockwiseGameAdvancesToFollowingPlayerAndKeepsGame() {
        LeaveFixture fixture = fixture(true, 2L, List.of(1L, 3L, 4L));

        Map<String, Object> result = fixture.gameService.leaveRoom(10L, 2L);

        assertFalse((Boolean) result.get("roomClosed"));
        assertTrue((Boolean) result.get("gameContinues"));
        assertEquals(GameStatus.PLAYING, fixture.game.getStatus());
        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        assertEquals(3L, fixture.game.getCurrentTurn());
        assertTrue(fixture.game.isClockwise());
        assertEquals(List.of(0, 1, 2), fixture.remainingPlayers.stream().map(GamePlayer::getSeatIndex).toList());
        verifyContinuationBroadcasts(fixture, 3L, 1);
    }

    @Test
    void currentPlayerLeavingCounterClockwiseGameAdvancesToPreviousPlayerAndKeepsDirection() {
        LeaveFixture fixture = fixture(false, 2L, List.of(1L, 3L, 4L));

        Map<String, Object> result = fixture.gameService.leaveRoom(10L, 2L);

        assertFalse((Boolean) result.get("roomClosed"));
        assertEquals(1L, fixture.game.getCurrentTurn());
        assertFalse(fixture.game.isClockwise());
        verifyContinuationBroadcasts(fixture, 1L, -1);
    }

    @Test
    void offlineTimeoutUsesTheSameReverseTurnAndContinuationHandling() {
        LeaveFixture fixture = fixture(false, 2L, List.of(1L, 3L, 4L));
        when(fixture.gamePlayerRepository.findByUser(fixture.leavingPlayer.getUser()))
                .thenReturn(List.of(fixture.leavingPlayer));

        boolean removed = fixture.gameService.handleOfflineTimeout(2L);

        assertTrue(removed);
        assertEquals(1L, fixture.game.getCurrentTurn());
        assertFalse(fixture.game.isClockwise());
        verifyContinuationBroadcasts(fixture, 1L, -1);
    }

    @Test
    void offlineTimeoutEndsRoomWhenOnlyOnePlayerRemains() {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        Room room = room(alice);
        Game game = game(room, 2L, true);
        GamePlayer alicePlayer = player(game, alice, 0);
        GamePlayer bobPlayer = player(game, bob, 1);

        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByUser(bob)).thenReturn(List.of(bobPlayer));
        when(gamePlayerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(bobPlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(List.of(alicePlayer));
        when(roomService.getRoomState(room)).thenReturn(new LinkedHashMap<>(Map.of("roomId", 10L)));

        boolean removed = gameService.handleOfflineTimeout(2L);

        assertTrue(removed);
        assertEquals(RoomStatus.CLOSED, room.getStatus());
        verify(wsService).broadcastRoomDeleted(10L, 20L, "bob left the room");
        verify(gamePlayerRepository).deleteAllByGame(game);
        verify(gameRepository).delete(game);
        verify(roomRepository).delete(room);
        verify(wsService, never()).broadcastPublicGamePatch(any());
    }

    private LeaveFixture fixture(boolean clockwise, Long currentTurn, List<Long> remainingIds) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        GameService gameService = new GameService(
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);

        User alice = user(1L, "alice");
        User bob = user(2L, "bob");
        User carol = user(3L, "carol");
        User dave = user(4L, "dave");
        Room room = room(alice);
        Game game = game(room, currentTurn, clockwise);
        List<GamePlayer> allPlayers = List.of(
                player(game, alice, 0),
                player(game, bob, 1),
                player(game, carol, 2),
                player(game, dave, 3));
        GamePlayer leavingPlayer = allPlayers.get(1);
        List<GamePlayer> remainingPlayers = new ArrayList<>(allPlayers.stream()
                .filter(player -> remainingIds.contains(player.getUser().getId()))
                .toList());

        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
        when(userRepository.findById(2L)).thenReturn(Optional.of(bob));
        when(gameRepository.findByRoom(room)).thenReturn(List.of(game));
        when(gamePlayerRepository.findByGameAndUser(game, bob)).thenReturn(Optional.of(leavingPlayer));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(remainingPlayers);
        when(gamePlayerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomService.getRoomState(room)).thenAnswer(invocation -> new LinkedHashMap<>(Map.of(
                "roomId", 10L,
                "playerCount", remainingPlayers.size(),
                "status", RoomStatus.PLAYING.name())));

        return new LeaveFixture(
                gameService,
                game,
                room,
                remainingPlayers,
                gameRepository,
                gamePlayerRepository,
                roomRepository,
                leavingPlayer,
                wsService);
    }

    private void verifyContinuationBroadcasts(LeaveFixture fixture, Long expectedTurn, int expectedDirection) {
        ArgumentCaptor<PublicGamePatch> patchCaptor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService).broadcastPublicGamePatch(patchCaptor.capture());
        PublicGamePatch patch = patchCaptor.getValue();
        assertEquals("PLAYER_LEFT", patch.type());
        assertEquals(2L, patch.actorUserId());
        assertEquals(expectedTurn, patch.currentPlayerId());
        assertEquals(expectedDirection, patch.direction());
        assertEquals(3, patch.players().size());
        verify(fixture.wsService).broadcastRoomState(any(), eq("PLAYER_LEFT"), eq("bob left the room"));
        verify(fixture.wsService).broadcastLobbyRoomState(any(), eq("PLAYER_LEFT"), eq("bob left the room"));
        verify(fixture.wsService, times(3)).sendPrivateHandPatch(any(), any(), any(), any(), any());
        verify(fixture.gameRepository, never()).delete(any());
        verify(fixture.roomRepository, never()).delete(any());
    }

    private static Room room(User host) {
        Room room = new Room();
        room.setId(10L);
        room.setHost(host);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(4);
        return room;
    }

    private static Game game(Room room, Long currentTurn, boolean clockwise) {
        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(currentTurn);
        game.setClockwise(clockwise);
        return game;
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static GamePlayer player(Game game, User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(List.of());
        return player;
    }

    private record LeaveFixture(GameService gameService,
                                Game game,
                                Room room,
                                List<GamePlayer> remainingPlayers,
                                GameRepository gameRepository,
                                GamePlayerRepository gamePlayerRepository,
                                RoomRepository roomRepository,
                                GamePlayer leavingPlayer,
                                GameWebSocketService wsService) {
    }
}
