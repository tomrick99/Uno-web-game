package com.uno.service;

import com.uno.entity.Game;
import com.uno.entity.GamePlayer;
import com.uno.entity.Room;
import com.uno.entity.User;
import com.uno.entity.enums.GameStatus;
import com.uno.repository.GamePlayerRepository;
import com.uno.repository.GameRepository;
import com.uno.repository.UserRepository;
import com.uno.websocket.GameWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisconnectGracePeriodServiceTest {

    private final DisconnectWaitingRegistry waitingRegistry = new DisconnectWaitingRegistry();
    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GamePlayerRepository gamePlayerRepository = mock(GamePlayerRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final GameWebSocketService wsService = mock(GameWebSocketService.class);
    private final GameService gameService = mock(GameService.class);
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);

    private DisconnectGracePeriodService service;
    private Game game;
    private User alice;
    private User bob;
    private User carol;
    private List<GamePlayer> players;

    @BeforeEach
    void setUp() {
        Room room = new Room();
        room.setId(10L);
        game = new Game();
        game.setId(20L);
        game.setRoom(room);
        game.setStatus(GameStatus.PLAYING);

        alice = user(1L, "alice");
        bob = user(2L, "bob");
        carol = user(3L, "carol");
        players = List.of(player(alice, 0), player(bob, 1), player(carol, 2));

        when(gameRepository.findById(20L)).thenReturn(Optional.of(game));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(userRepository.findByUsername("carol")).thenReturn(Optional.of(carol));
        when(gamePlayerRepository.findByGameAndUser(game, alice)).thenReturn(Optional.of(players.get(0)));
        when(gamePlayerRepository.findByGameOrderBySeatIndexAsc(game)).thenReturn(players);
        doReturn(scheduledFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

        service = new DisconnectGracePeriodService(
                waitingRegistry,
                gameRepository,
                gamePlayerRepository,
                userRepository,
                wsService,
                gameService,
                taskScheduler,
                Duration.ofSeconds(60),
                clock);
    }

    @Test
    void allPlayersOnlineDoesNotStartWaiting() {
        subscribeAllPlayers();

        assertFalse(waitingRegistry.isWaiting(20L));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void closingOneOfMultipleSessionsDoesNotDisconnectPlayer() {
        subscribeAllPlayers();
        service.gameSessionSubscribed("alice-second-session", "alice", 20L);

        service.gameSessionDisconnected("alice-session");

        assertFalse(waitingRegistry.isWaiting(20L));
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void realDisconnectStartsSixtySecondWaitingAndReconnectCancelsIt() {
        subscribeAllPlayers();
        service.gameSessionDisconnected("alice-session");

        Map<String, Object> state = waitingRegistry.snapshot(20L, clock);
        assertTrue((Boolean) state.get("waiting"));
        assertEquals(60L, state.get("remainingSeconds"));
        assertEquals(clock.millis() + 60_000L, state.get("deadlineEpochMs"));
        verify(wsService).broadcastDisconnectWaitingState(
                eq(20L), eq(10L), eq("PLAYER_DISCONNECTED"), stateWithWaiting(), eq("alice disconnected"));

        service.gameSessionSubscribed("alice-reconnected", "alice", 20L);

        assertFalse(waitingRegistry.isWaiting(20L));
        verify(scheduledFuture).cancel(false);
        verify(gameService, never()).removeDisconnectedPlayer(any(), any(), any());
    }

    @Test
    void snapshotRetainsRequiredVotersForRefreshDuringWaiting() {
        subscribeAllPlayers();
        service.gameSessionDisconnected("alice-session");

        Map<String, Object> refreshedState = waitingRegistry.snapshot(20L);

        assertEquals(2, refreshedState.get("requiredCount"));
        assertEquals(java.util.Set.of(2L, 3L), refreshedState.get("requiredUserIds"));
    }

    @Test
    void timeoutRemovesDisconnectedPlayer() {
        subscribeAllPlayers();
        service.gameSessionDisconnected("alice-session");
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(timeout.capture(), any(Instant.class));

        timeout.getValue().run();

        assertFalse(waitingRegistry.isWaiting(20L));
        verify(gameService).removeDisconnectedPlayer(20L, 1L, "DISCONNECT_TIMEOUT");
    }

    @Test
    void unanimousOnlinePlayersCanContinueEarlyButOneVoteCannot() {
        subscribeAllPlayers();
        service.gameSessionDisconnected("alice-session");

        Map<String, Object> firstVote = service.voteToContinue(20L, 2L);
        assertTrue((Boolean) firstVote.get("waiting"));
        assertEquals(1L, firstVote.get("approvedCount"));
        assertEquals(2, firstVote.get("requiredCount"));
        verify(gameService, never()).removeDisconnectedPlayer(any(), any(), any());

        Map<String, Object> result = service.voteToContinue(20L, 3L);

        assertEquals(false, result.get("waiting"));
        assertEquals("UNANIMOUS_CONTINUE", result.get("resolution"));
        assertFalse(waitingRegistry.isWaiting(20L));
        verify(gameService).removeDisconnectedPlayer(20L, 1L, "UNANIMOUS_CONTINUE");
    }

    @Test
    void disconnectedOrOutsidePlayerCannotVote() {
        subscribeAllPlayers();
        service.gameSessionDisconnected("alice-session");

        assertThrows(IllegalArgumentException.class, () -> service.voteToContinue(20L, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.voteToContinue(20L, 99L));
        assertTrue(waitingRegistry.isWaiting(20L));
    }

    private void subscribeAllPlayers() {
        service.gameSessionSubscribed("alice-session", "alice", 20L);
        service.gameSessionSubscribed("bob-session", "bob", 20L);
        service.gameSessionSubscribed("carol-session", "carol", 20L);
    }

    private Map<String, Object> stateWithWaiting() {
        return org.mockito.ArgumentMatchers.argThat(state -> Boolean.TRUE.equals(state.get("waiting"))
                && Long.valueOf(60L).equals(state.get("remainingSeconds"))
                && Long.valueOf(1L).equals(state.get("disconnectedUserId")));
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private GamePlayer player(User user, int seatIndex) {
        GamePlayer player = new GamePlayer();
        player.setGame(game);
        player.setUser(user);
        player.setSeatIndex(seatIndex);
        return player;
    }
}
