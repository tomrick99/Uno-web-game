package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
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
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.uno.entity.enums.CardColor.RED;
import static com.uno.entity.enums.CardColor.WILD;
import static com.uno.entity.enums.CardType.NUMBER;
import static com.uno.entity.enums.CardType.WILD_DRAW_SIX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceTurnTimerTest {

    @Test
    void expiredTurnDrawsOneCardAndAdvancesWithFreshSharedDeadline() {
        Fixture fixture = fixture(1L, true, List.of(1L, 2L, 3L));
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() - 1);

        fixture.service.finishExpiredTimedGames();

        assertEquals(2, fixture.alice.getHandCards().size());
        assertEquals(1, fixture.alice.getConsecutiveTurnTimeouts());
        assertEquals(2L, fixture.game.getCurrentTurn());
        assertTrue(fixture.game.getTurnEndsAtEpochMs() > System.currentTimeMillis());

        ArgumentCaptor<PublicGamePatch> patchCaptor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService).broadcastPublicGamePatch(patchCaptor.capture());
        PublicGamePatch patch = patchCaptor.getValue();
        assertEquals("TURN_TIMED_OUT", patch.type());
        assertEquals(2L, patch.currentPlayerId());
        assertEquals(fixture.game.getTurnEndsAtEpochMs(), patch.turnEndsAtEpochMs());
        assertNotNull(patch.timestamp());
    }

    @Test
    void expiredPenaltyTurnAcceptsFullStackWithoutStrategicCardChoice() {
        Fixture fixture = fixture(2L, false, List.of(1L, 2L, 3L));
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() - 1);
        fixture.game.setPendingDrawCount(4);
        fixture.game.setPendingDrawType(PendingDrawType.DRAW_STACK);
        fixture.bob.setHandCards(new ArrayList<>(List.of(new Card(WILD, WILD_DRAW_SIX, 60))));

        fixture.service.finishExpiredTimedGames();

        assertEquals(5, fixture.bob.getHandCards().size());
        assertEquals(1, fixture.bob.getConsecutiveTurnTimeouts());
        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, fixture.game.getPendingDrawType());
        assertEquals(1L, fixture.game.getCurrentTurn());
        assertFalse(fixture.game.isClockwise());
    }

    @Test
    void successfulManualActionResetsConsecutiveTimeoutCount() {
        Fixture fixture = fixture(2L, true, List.of(1L, 2L, 3L));
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() + 30_000L);
        fixture.bob.setConsecutiveTurnTimeouts(2);

        fixture.service.drawCard(20L, 2L);

        assertEquals(0, fixture.bob.getConsecutiveTurnTimeouts());
        assertEquals(3L, fixture.game.getCurrentTurn());
        assertTrue(fixture.game.getTurnEndsAtEpochMs() > System.currentTimeMillis());
    }

    @Test
    void actionArrivingAfterDeadlineResolvesTimeoutInsteadOfApplyingLateAction() {
        Fixture fixture = fixture(1L, true, List.of(1L, 2L, 3L));
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() - 1);

        Map<String, Object> acknowledgement = fixture.service.drawCard(20L, 1L);

        assertEquals("TURN_TIMED_OUT", acknowledgement.get("type"));
        assertEquals(2, fixture.alice.getHandCards().size());
        assertEquals(1, fixture.alice.getConsecutiveTurnTimeouts());
        assertEquals(2L, fixture.game.getCurrentTurn());
    }

    @Test
    void thirdConsecutiveTimeoutRemovesPlayerUsingExistingReverseLeaveFlow() {
        Fixture fixture = fixture(2L, false, List.of(1L, 2L, 3L, 4L));
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() - 1);
        fixture.bob.setConsecutiveTurnTimeouts(2);
        List<GamePlayer> remaining = fixture.players.stream()
                .filter(player -> !player.getUser().getId().equals(2L))
                .toList();
        when(fixture.playerRepository.findByGameOrderBySeatIndexAsc(fixture.game))
                .thenReturn(fixture.players, remaining);
        when(fixture.playerRepository.findByGameAndUser(fixture.game, fixture.bob.getUser()))
                .thenReturn(Optional.of(fixture.bob));
        when(fixture.roomRepository.findById(10L)).thenReturn(Optional.of(fixture.room));
        when(fixture.userRepository.findById(2L)).thenReturn(Optional.of(fixture.bob.getUser()));
        when(fixture.gameRepository.findByRoom(fixture.room)).thenReturn(List.of(fixture.game));
        when(fixture.roomService.getRoomState(fixture.room)).thenReturn(new LinkedHashMap<>(Map.of(
                "roomId", 10L,
                "status", RoomStatus.PLAYING.name(),
                "playerCount", 3)));

        fixture.service.finishExpiredTimedGames();

        assertEquals(3, fixture.bob.getConsecutiveTurnTimeouts());
        assertEquals(1L, fixture.game.getCurrentTurn());
        assertFalse(fixture.game.isClockwise());
        verify(fixture.playerRepository).delete(fixture.bob);

        ArgumentCaptor<PublicGamePatch> patchCaptor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService).broadcastPublicGamePatch(patchCaptor.capture());
        PublicGamePatch patch = patchCaptor.getValue();
        assertEquals("PLAYER_LEFT", patch.type());
        assertEquals(2L, patch.actorUserId());
        assertEquals(1L, patch.currentPlayerId());
        assertEquals(3, patch.players().size());
        verify(fixture.wsService).broadcastRoomState(any(), eq("PLAYER_LEFT"),
                eq("bob was removed after 3 consecutive turn timeouts"));
    }

    private Fixture fixture(Long currentTurn, boolean clockwise, List<Long> playerIds) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        GameService service = new GameService(
                gameRepository,
                playerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);

        User aliceUser = user(1L, "alice");
        User bobUser = user(2L, "bob");
        User carolUser = user(3L, "carol");
        User daveUser = user(4L, "dave");
        Room room = new Room();
        room.setId(10L);
        room.setHost(aliceUser);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(playerIds.size());
        room.setGameMode(GameMode.NO_MERCY);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(currentTurn);
        game.setClockwise(clockwise);
        game.setCurrentColor(RED);
        game.setDrawPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":1},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":2},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":3},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":4},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":5}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alice = player(game, aliceUser, 0);
        GamePlayer bob = player(game, bobUser, 1);
        GamePlayer carol = player(game, carolUser, 2);
        GamePlayer dave = player(game, daveUser, 3);
        List<GamePlayer> allPlayers = List.of(alice, bob, carol, dave);
        List<GamePlayer> players = allPlayers.stream()
                .filter(player -> playerIds.contains(player.getUser().getId()))
                .toList();

        when(gameRepository.findByStatus(GameStatus.PLAYING)).thenReturn(List.of(game));
        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.findByGameAndUser(game, aliceUser)).thenReturn(Optional.of(alice));
        when(playerRepository.findByGameAndUser(game, bobUser)).thenReturn(Optional.of(bob));
        when(playerRepository.findByGameAndUser(game, carolUser)).thenReturn(Optional.of(carol));
        when(playerRepository.findByGameAndUser(game, daveUser)).thenReturn(Optional.of(dave));
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(2L)).thenReturn(bobUser);
        when(userRepository.getReferenceById(1L)).thenReturn(aliceUser);

        return new Fixture(
                service,
                game,
                room,
                players,
                alice,
                bob,
                gameRepository,
                playerRepository,
                roomRepository,
                userRepository,
                wsService,
                roomService);
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
        player.setHandCards(new ArrayList<>(List.of(new Card(RED, NUMBER, seatIndex + 1))));
        return player;
    }

    private record Fixture(GameService service,
                           Game game,
                           Room room,
                           List<GamePlayer> players,
                           GamePlayer alice,
                           GamePlayer bob,
                           GameRepository gameRepository,
                           GamePlayerRepository playerRepository,
                           RoomRepository roomRepository,
                           UserRepository userRepository,
                           GameWebSocketService wsService,
                           RoomService roomService) {
    }
}
