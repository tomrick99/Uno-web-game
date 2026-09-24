package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
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
import static com.uno.entity.enums.CardType.NUMBER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceTurnTimeoutTest {

    @Test
    void ordinaryTimeoutDrawsOneCardAndEndsTurnWithoutTouchingGameTimer() {
        Fixture fixture = fixture(1L);
        long gameDeadline = System.currentTimeMillis() + 600_000L;
        fixture.game.setTimerEndsAtEpochMs(gameDeadline);

        boolean resolved = fixture.service.resolveTurnTimeout(fixture.game, System.currentTimeMillis());

        assertTrue(resolved);
        assertEquals(2, fixture.alice.getHandCards().size());
        assertEquals(1, fixture.alice.getConsecutiveTurnTimeouts());
        assertEquals(2L, fixture.game.getCurrentTurn());
        assertEquals(gameDeadline, fixture.game.getTimerEndsAtEpochMs());
        assertTrue(fixture.game.getTurnEndsAtEpochMs() > System.currentTimeMillis());

        ArgumentCaptor<PublicGamePatch> patch = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService).broadcastPublicGamePatch(patch.capture());
        assertEquals("TURN_TIMEOUT", patch.getValue().type());
        assertEquals(fixture.game.getTurnEndsAtEpochMs(), patch.getValue().turnEndsAtEpochMs());
    }

    @Test
    void penaltyTimeoutAcceptsEntirePenaltyInsteadOfChoosingAStack() {
        Fixture fixture = fixture(2L);
        fixture.game.setPendingDrawCount(4);
        fixture.game.setPendingDrawType(PendingDrawType.WILD_DRAW_FOUR_CHAIN);
        fixture.game.setLastPenaltyPlayerId(1L);

        fixture.service.resolveTurnTimeout(fixture.game, System.currentTimeMillis());

        assertEquals(5, fixture.bob.getHandCards().size());
        assertEquals(1, fixture.bob.getConsecutiveTurnTimeouts());
        assertEquals(0, fixture.game.getPendingDrawCount());
        assertEquals(PendingDrawType.NONE, fixture.game.getPendingDrawType());
        assertEquals(3L, fixture.game.getCurrentTurn());
    }

    @Test
    void successfulManualActionResetsConsecutiveTimeouts() {
        Fixture fixture = fixture(1L);
        fixture.alice.setConsecutiveTurnTimeouts(2);
        fixture.game.setTurnEndsAtEpochMs(System.currentTimeMillis() + 30_000L);
        when(fixture.gameRepository.findById(20L)).thenReturn(Optional.of(fixture.game));
        when(fixture.userRepository.getReferenceById(1L)).thenReturn(fixture.alice.getUser());
        when(fixture.playerRepository.findByGameAndUser(fixture.game, fixture.alice.getUser()))
                .thenReturn(Optional.of(fixture.alice));

        fixture.service.drawCard(20L, 1L);

        assertEquals(0, fixture.alice.getConsecutiveTurnTimeouts());
        assertEquals(2L, fixture.game.getCurrentTurn());
    }

    @Test
    void thirdConsecutiveTimeoutRemovesPlayerUsingExistingDepartureFlow() {
        Fixture fixture = fixture(1L);
        fixture.alice.setConsecutiveTurnTimeouts(2);
        List<GamePlayer> remaining = List.of(fixture.bob, fixture.carol);
        when(fixture.roomRepository.findById(10L)).thenReturn(Optional.of(fixture.room));
        when(fixture.userRepository.findById(1L)).thenReturn(Optional.of(fixture.alice.getUser()));
        when(fixture.gameRepository.findByRoom(fixture.room)).thenReturn(List.of(fixture.game));
        when(fixture.playerRepository.findByGameAndUser(fixture.game, fixture.alice.getUser()))
                .thenReturn(Optional.of(fixture.alice));
        when(fixture.playerRepository.findByGameOrderBySeatIndexAsc(fixture.game))
                .thenReturn(fixture.players, remaining);

        fixture.service.resolveTurnTimeout(fixture.game, System.currentTimeMillis());

        verify(fixture.playerRepository).delete(fixture.alice);
        assertEquals(2L, fixture.game.getCurrentTurn());
        assertEquals(RoomStatus.PLAYING, fixture.room.getStatus());
        verify(fixture.wsService).broadcastRoomState(any(), eq("PLAYER_AFK_REMOVED"), any());
        ArgumentCaptor<PublicGamePatch> patch = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService).broadcastPublicGamePatch(patch.capture());
        assertEquals("PLAYER_AFK_REMOVED", patch.getValue().type());
        assertEquals(2, patch.getValue().players().size());
    }

    private Fixture fixture(Long currentTurn) {
        GameRepository gameRepository = mock(GameRepository.class);
        GamePlayerRepository playerRepository = mock(GamePlayerRepository.class);
        RoomRepository roomRepository = mock(RoomRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GameWebSocketService wsService = mock(GameWebSocketService.class);
        RoomService roomService = mock(RoomService.class);
        GameService service = new GameService(
                gameRepository, playerRepository, roomRepository, userRepository, wsService, roomService);

        User aliceUser = user(1L, "alice");
        User bobUser = user(2L, "bob");
        User carolUser = user(3L, "carol");
        Room room = new Room();
        room.setId(10L);
        room.setHost(aliceUser);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(3);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(currentTurn);
        game.setClockwise(true);
        game.setTurnEndsAtEpochMs(System.currentTimeMillis() - 1L);
        game.setDrawPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":1},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":2},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":3},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":4},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":5}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alice = player(game, aliceUser, 0);
        GamePlayer bob = player(game, bobUser, 1);
        GamePlayer carol = player(game, carolUser, 2);
        List<GamePlayer> players = new ArrayList<>(List.of(alice, bob, carol));

        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(roomService.getRoomState(room)).thenReturn(new LinkedHashMap<>(Map.of(
                "roomId", 10L,
                "status", RoomStatus.PLAYING.name(),
                "playerCount", players.size())));

        return new Fixture(service, game, room, players, alice, bob, carol,
                gameRepository, playerRepository, roomRepository, userRepository, wsService);
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
        player.setHandCards(new ArrayList<>(List.of(new Card(RED, NUMBER, seat + 6))));
        return player;
    }

    private record Fixture(GameService service,
                           Game game,
                           Room room,
                           List<GamePlayer> players,
                           GamePlayer alice,
                           GamePlayer bob,
                           GamePlayer carol,
                           GameRepository gameRepository,
                           GamePlayerRepository playerRepository,
                           RoomRepository roomRepository,
                           UserRepository userRepository,
                           GameWebSocketService wsService) {
    }
}
