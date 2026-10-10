package com.uno.websocket;

import com.uno.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameConnectionEventListenerTest {

    @Test
    void lobbyToGameConnectionReplacementForMultiplePlayersDoesNotStartDisconnectWaiting() {
        ListenerFixture fixture = fixture();
        List<String> players = List.of("alice", "bob", "charlie", "diana");

        for (String player : players) {
            fixture.listener.registerConnectedSession(player + "-lobby", player);
        }
        for (String player : players) {
            fixture.listener.registerDisconnectedSession(player + "-lobby");
            fixture.listener.registerConnectedSession(player + "-game", player);
            fixture.listener.registerGameSubscription(player + "-game");
        }

        fixture.runPendingChecks();
        verify(fixture.gameService, never()).playerDisconnected(anyString());

        fixture.listener.registerDisconnectedSession("bob-game");
        fixture.runPendingChecks();
        verify(fixture.gameService).playerDisconnected("bob");
    }

    @Test
    void closingOneOfMultipleSessionsDoesNotReportPlayerDisconnected() {
        ListenerFixture fixture = fixture();
        fixture.listener.registerConnectedSession("alice-game-1", "alice");
        fixture.listener.registerConnectedSession("alice-game-2", "alice");
        fixture.listener.registerGameSubscription("alice-game-1");
        fixture.listener.registerGameSubscription("alice-game-2");

        fixture.listener.registerDisconnectedSession("alice-game-1");
        fixture.runPendingChecks();

        verify(fixture.gameService, never()).playerDisconnected(anyString());
    }

    @Test
    void samePrincipalOnNewGameSessionIsReportedAsTheReturningPlayer() {
        ListenerFixture fixture = fixture();
        fixture.listener.registerConnectedSession("alice-before-drop", "alice");
        fixture.listener.registerGameSubscription("alice-before-drop");
        fixture.listener.registerDisconnectedSession("alice-before-drop");
        fixture.runPendingChecks();
        verify(fixture.gameService).playerDisconnected("alice");

        fixture.listener.registerConnectedSession("alice-after-drop", "alice");
        fixture.listener.registerGameSubscription("alice-after-drop");

        verify(fixture.gameService, times(2)).playerConnected("alice");
    }

    @SuppressWarnings("unchecked")
    private ListenerFixture fixture() {
        GameService gameService = mock(GameService.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        List<Runnable> pendingChecks = new ArrayList<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            pendingChecks.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        return new ListenerFixture(
                gameService,
                new GameConnectionEventListener(gameService, taskScheduler),
                pendingChecks
        );
    }

    private record ListenerFixture(GameService gameService,
                                   GameConnectionEventListener listener,
                                   List<Runnable> pendingChecks) {
        private void runPendingChecks() {
            List<Runnable> checks = new ArrayList<>(pendingChecks);
            pendingChecks.clear();
            checks.forEach(Runnable::run);
        }
    }
}
