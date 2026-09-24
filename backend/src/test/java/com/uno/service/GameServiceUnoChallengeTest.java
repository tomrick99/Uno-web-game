package com.uno.service;

import com.uno.dto.realtime.PublicGamePatch;
import com.uno.dto.realtime.PublicUnoWindow;
import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.CardColor;
import com.uno.entity.enums.CardType;
import com.uno.entity.enums.GameMode;
import com.uno.entity.enums.GameStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceUnoChallengeTest {

    @Test
    void successfulCallEndsWindowAndFutureOneCardPlayCreatesNewEvent() {
        Fixture fixture = fixture();

        fixture.service.playCard(20L, 1L, 0, null);
        PublicGamePatch firstPlay = capturePatches(fixture, 1).get(0);
        PublicUnoWindow firstWindow = firstPlay.unoWindows().get(0);
        assertFalse(fixture.alice.isSaidUno());

        Map<String, Object> refreshedState = fixture.service.getGameState(20L);
        List<?> refreshedWindows = (List<?>) refreshedState.get("unoWindows");
        assertEquals(1, refreshedWindows.size());
        assertEquals(firstWindow.eventId(), ((PublicUnoWindow) refreshedWindows.get(0)).eventId());

        fixture.service.callUno(20L, 1L, firstWindow.eventId());
        List<PublicGamePatch> afterCall = capturePatches(fixture, 2);
        assertEquals("UNO_CALLED", afterCall.get(1).type());
        assertTrue(afterCall.get(1).unoWindows().isEmpty());
        assertTrue(fixture.alice.isSaidUno());
        assertEquals(1, fixture.alice.getHandCards().size());

        fixture.alice.setHandCards(new ArrayList<>(List.of(
                new Card(CardColor.RED, CardType.NUMBER, 6),
                new Card(CardColor.BLUE, CardType.NUMBER, 7))));
        fixture.game.setCurrentTurn(1L);
        fixture.game.setCurrentColor(CardColor.RED);

        fixture.service.playCard(20L, 1L, 0, null);
        List<PublicGamePatch> afterSecondPlay = capturePatches(fixture, 3);
        PublicUnoWindow secondWindow = afterSecondPlay.get(2).unoWindows().get(0);
        assertNotEquals(firstWindow.eventId(), secondWindow.eventId());
        assertFalse(fixture.alice.isSaidUno());
    }

    @Test
    void challengeDrawsTwoExactlyOnceAndDoesNotChangeTheTurn() {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        PublicUnoWindow window = capturePatches(fixture, 1).get(0).unoWindows().get(0);
        Long turnAfterPlay = fixture.game.getCurrentTurn();

        fixture.service.challengeUno(20L, 2L, window.eventId());

        assertEquals(3, fixture.alice.getHandCards().size());
        assertEquals(turnAfterPlay, fixture.game.getCurrentTurn());
        assertFalse(fixture.alice.isSaidUno());
        List<PublicGamePatch> afterChallenge = capturePatches(fixture, 2);
        assertEquals("UNO_CHALLENGED", afterChallenge.get(1).type());
        assertTrue(afterChallenge.get(1).unoWindows().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, window.eventId()));
        assertEquals(3, fixture.alice.getHandCards().size());
    }

    @Test
    void expiredWindowClosesWithoutPenalty() {
        Fixture fixture = fixture();
        fixture.alice.setHandCards(new ArrayList<>(List.of(
                new Card(CardColor.BLUE, CardType.NUMBER, 7))));
        fixture.service.openUnoWindow(
                fixture.game,
                fixture.alice,
                System.currentTimeMillis() - GameService.UNO_CHALLENGE_WINDOW_MS - 1);

        fixture.service.finishExpiredTimedGames();

        assertEquals(1, fixture.alice.getHandCards().size());
        assertFalse(fixture.alice.isSaidUno());
        PublicGamePatch expiryPatch = capturePatches(fixture, 1).get(0);
        assertEquals("UNO_WINDOW_EXPIRED", expiryPatch.type());
        assertTrue(expiryPatch.unoWindows().isEmpty());
    }

    @Test
    void dropEffectThatLeavesOneCardStillRequiresAnExplicitUnoCall() {
        Fixture fixture = fixture();
        fixture.game.getRoom().setGameMode(GameMode.NO_MERCY);
        fixture.alice.setHandCards(new ArrayList<>(List.of(
                new Card(CardColor.RED, CardType.DISCARD_ALL_COLOR, 0),
                new Card(CardColor.RED, CardType.NUMBER, 6),
                new Card(CardColor.BLUE, CardType.NUMBER, 7))));

        fixture.service.playCard(20L, 1L, 0, null);

        PublicGamePatch patch = capturePatches(fixture, 1).get(0);
        assertEquals(1, fixture.alice.getHandCards().size());
        assertFalse(fixture.alice.isSaidUno());
        assertEquals(1, patch.unoWindows().size());
        assertEquals(1L, patch.unoWindows().get(0).targetPlayerId());
    }

    private List<PublicGamePatch> capturePatches(Fixture fixture, int expectedCount) {
        ArgumentCaptor<PublicGamePatch> captor = ArgumentCaptor.forClass(PublicGamePatch.class);
        verify(fixture.wsService, times(expectedCount)).broadcastPublicGamePatch(captor.capture());
        return captor.getAllValues();
    }

    private Fixture fixture() {
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
        Room room = new Room();
        room.setId(10L);
        room.setHost(aliceUser);
        room.setStatus(RoomStatus.PLAYING);
        room.setMaxPlayers(2);
        room.setGameMode(GameMode.CLASSIC);

        Game game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);
        game.setCurrentTurn(1L);
        game.setClockwise(true);
        game.setCurrentColor(CardColor.RED);
        game.setTurnEndsAtEpochMs(System.currentTimeMillis() + 30_000L);
        game.setDrawPileJson("[{\"color\":\"GREEN\",\"type\":\"NUMBER\",\"value\":1},{\"color\":\"YELLOW\",\"type\":\"NUMBER\",\"value\":2},{\"color\":\"BLUE\",\"type\":\"NUMBER\",\"value\":3},{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":4}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alice = player(game, aliceUser, 0, List.of(
                new Card(CardColor.RED, CardType.NUMBER, 5),
                new Card(CardColor.BLUE, CardType.NUMBER, 7)));
        GamePlayer bob = player(game, bobUser, 1, List.of(
                new Card(CardColor.RED, CardType.NUMBER, 1),
                new Card(CardColor.GREEN, CardType.NUMBER, 2)));
        List<GamePlayer> players = List.of(alice, bob);

        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(gameRepository.findByStatus(GameStatus.PLAYING)).thenReturn(List.of(game));
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        when(playerRepository.findByGameAndUser(game, aliceUser)).thenReturn(Optional.of(alice));
        when(playerRepository.findByGameAndUser(game, bobUser)).thenReturn(Optional.of(bob));
        when(playerRepository.save(any(GamePlayer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(1L)).thenReturn(aliceUser);
        when(userRepository.getReferenceById(2L)).thenReturn(bobUser);

        return new Fixture(service, game, alice, wsService);
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static GamePlayer player(Game game, User user, int seatIndex, List<Card> cards) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>(cards));
        return player;
    }

    private record Fixture(GameService service,
                           Game game,
                           GamePlayer alice,
                           GameWebSocketService wsService) {
    }
}
