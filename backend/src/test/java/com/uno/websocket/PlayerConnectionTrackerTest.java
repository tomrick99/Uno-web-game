package com.uno.websocket;

import com.uno.service.GameService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlayerConnectionTrackerTest {

    @Test
    void lastDisconnectSchedulesRemovalForThirtySecondsLater() {
        GameService gameService = mock(GameService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> future);
        PlayerConnectionTracker tracker = new PlayerConnectionTracker(gameService, scheduler);
        Instant beforeDisconnect = Instant.now();

        tracker.playerConnected("session-1", 7L);
        tracker.playerDisconnected("session-1");

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> deadlineCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(taskCaptor.capture(), deadlineCaptor.capture());
        Instant deadline = deadlineCaptor.getValue();
        assertFalse(deadline.isBefore(beforeDisconnect.plusSeconds(29)));
        assertTrue(deadline.isBefore(Instant.now().plusSeconds(31)));

        taskCaptor.getValue().run();

        verify(gameService).handleOfflineTimeout(7L);
    }

    @Test
    void reconnectBeforeTimeoutCancelsRemoval() {
        GameService gameService = mock(GameService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> future);
        PlayerConnectionTracker tracker = new PlayerConnectionTracker(gameService, scheduler);

        tracker.playerConnected("session-1", 7L);
        tracker.playerDisconnected("session-1");
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(taskCaptor.capture(), any(Instant.class));

        tracker.playerConnected("session-2", 7L);
        taskCaptor.getValue().run();

        verify(future).cancel(false);
        verify(gameService, never()).handleOfflineTimeout(any());
    }

    @Test
    void oneDisconnectedTabDoesNotMarkPlayerOfflineWhileAnotherSessionIsConnected() {
        GameService gameService = mock(GameService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        PlayerConnectionTracker tracker = new PlayerConnectionTracker(gameService, scheduler);

        tracker.playerConnected("session-1", 7L);
        tracker.playerConnected("session-2", 7L);
        tracker.playerDisconnected("session-1");

        verifyNoInteractions(scheduler);
        verifyNoInteractions(gameService);
    }
}
