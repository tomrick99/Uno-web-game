package com.uno.service;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameServiceUnoCallTest {

    @Test
    void callUnoWinsOnceAndMakesLaterChallengeHarmless() {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        assertEquals(1, ((List<?>) fixture.service.getGameState(20L).get("unoWindows")).size());
        long windowId = activeWindowId(fixture);

        Map<String, Object> callAck = fixture.service.callUno(20L, 1L, windowId);

        assertEquals("UNO_CALLED", callAck.get("type"));
        assertTrue(fixture.alice.isSaidUno());
        assertEquals(1, fixture.alice.getHandCards().size());
        assertEquals(List.of(), fixture.service.getGameState(20L).get("unoWindows"));
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, 1L, windowId));
        assertEquals(1, fixture.alice.getHandCards().size());
    }

    @Test
    void challengeDrawsTwoCardsAndCannotBeAppliedTwice() {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        long windowId = activeWindowId(fixture);

        Map<String, Object> challengeAck = fixture.service.challengeUno(20L, 2L, 1L, windowId);

        assertEquals("UNO_CHALLENGED", challengeAck.get("type"));
        assertEquals(2, challengeAck.get("drawCount"));
        assertEquals(3, fixture.alice.getHandCards().size());
        assertFalse(fixture.alice.isSaidUno());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, 1L, windowId));
        assertEquals(3, fixture.alice.getHandCards().size());
    }

    @Test
    void simultaneousCallAndChallengeHaveExactlyOneWinner() throws Exception {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        long windowId = activeWindowId(fixture);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> attempt(start, successes, () -> fixture.service.callUno(20L, 1L, windowId)));
        executor.submit(() -> attempt(start, successes,
                () -> fixture.service.challengeUno(20L, 2L, 1L, windowId)));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertTrue((fixture.alice.isSaidUno() && fixture.alice.getHandCards().size() == 1)
                || (!fixture.alice.isSaidUno() && fixture.alice.getHandCards().size() == 3));
    }

    @Test
    void expiredWindowClosesWithoutPenaltyAndAllowsANewWindow() throws Exception {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        long firstWindowId = activeWindowId(fixture);
        Thread.sleep(GameService.UNO_CALL_WINDOW_MS + 100L);

        fixture.service.finishExpiredTimedGames();

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, 1L, firstWindowId));
        assertEquals(1, fixture.alice.getHandCards().size());
        assertEquals(List.of(), fixture.service.getGameState(20L).get("unoWindows"));

        fixture.service.drawCard(20L, 2L);
        fixture.service.drawCard(20L, 1L);
        fixture.service.drawCard(20L, 2L);
        fixture.service.playCard(20L, 1L, 0, null);

        long secondWindowId = activeWindowId(fixture);
        assertNotEquals(firstWindowId, secondWindowId);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, 1L, firstWindowId));
        fixture.service.callUno(20L, 1L, secondWindowId);
        fixture.service.finishExpiredTimedGames();
        assertEquals(1, fixture.alice.getHandCards().size());
    }

    @Test
    void successfulCallDoesNotBlockANewWindowForTheSamePlayer() {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        long firstWindowId = activeWindowId(fixture);
        fixture.service.callUno(20L, 1L, firstWindowId);

        fixture.service.drawCard(20L, 2L);
        fixture.service.drawCard(20L, 1L);
        fixture.service.drawCard(20L, 2L);
        fixture.service.playCard(20L, 1L, 0, null);

        long secondWindowId = activeWindowId(fixture);
        assertNotEquals(firstWindowId, secondWindowId);
        assertFalse(fixture.alice.isSaidUno());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.callUno(20L, 1L, firstWindowId));
        assertEquals("UNO_CALLED", fixture.service.callUno(20L, 1L, secondWindowId).get("type"));
    }

    @Test
    void successfulChallengeDoesNotBlockANewWindowOrAllowOldEventReuse() {
        Fixture fixture = fixture();
        fixture.service.playCard(20L, 1L, 0, null);
        long firstWindowId = activeWindowId(fixture);
        fixture.service.challengeUno(20L, 2L, 1L, firstWindowId);

        fixture.service.drawCard(20L, 2L);
        fixture.service.playCard(20L, 1L, 0, null);
        fixture.service.drawCard(20L, 2L);
        fixture.service.playCard(20L, 1L, 0, null);

        long secondWindowId = activeWindowId(fixture);
        assertNotEquals(firstWindowId, secondWindowId);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.challengeUno(20L, 2L, 1L, firstWindowId));
        assertEquals(1, fixture.alice.getHandCards().size());
        fixture.service.challengeUno(20L, 2L, 1L, secondWindowId);
        assertEquals(3, fixture.alice.getHandCards().size());
    }

    @SuppressWarnings("unchecked")
    private static long activeWindowId(Fixture fixture) {
        List<Map<String, Object>> windows =
                (List<Map<String, Object>>) fixture.service.getGameState(20L).get("unoWindows");
        assertEquals(1, windows.size());
        return ((Number) windows.get(0).get("windowId")).longValue();
    }

    private static void attempt(CountDownLatch start, AtomicInteger successes, Runnable action) {
        try {
            start.await();
            action.run();
            successes.incrementAndGet();
        } catch (IllegalArgumentException ignored) {
            // The room lock guarantees that the losing request observes the resolved window.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Fixture fixture() {
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
        game.setCurrentColor(CardColor.RED);
        game.setTurnEndsAtEpochMs(System.currentTimeMillis() + 30_000L);
        game.setDrawPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":3},"
                + "{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":4},"
                + "{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":5},"
                + "{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":6},"
                + "{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":7},"
                + "{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":8}]");
        game.setDiscardPileJson("[{\"color\":\"RED\",\"type\":\"NUMBER\",\"value\":9}]");

        GamePlayer alice = player(game, aliceUser, 0, List.of(
                new Card(CardColor.RED, CardType.NUMBER, 1),
                new Card(CardColor.BLUE, CardType.NUMBER, 2)));
        GamePlayer bob = player(game, bobUser, 1, List.of(
                new Card(CardColor.GREEN, CardType.NUMBER, 6),
                new Card(CardColor.YELLOW, CardType.NUMBER, 7)));
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

        return new Fixture(service, game, alice, bob);
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private static GamePlayer player(Game game, User user, int seatIndex, List<Card> hand) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        player.setHandCards(new ArrayList<>(hand));
        return player;
    }

    private record Fixture(GameService service, Game game, GamePlayer alice, GamePlayer bob) {}
}
