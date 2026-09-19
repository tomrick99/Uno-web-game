package com.uno.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class PlayerConnectionServiceTest {

    @Test
    void defaultOfflineTimeoutIsThirtySeconds() {
        assertEquals(30_000L, PlayerConnectionService.DEFAULT_OFFLINE_TIMEOUT_MILLIS);
    }

    @Test
    void reconnectBeforeTimeoutCancelsRemoval() {
        GameService gameService = mock(GameService.class);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PlayerConnectionService service = new PlayerConnectionService(gameService, scheduler, 80L);

        try {
            service.playerConnected("alice", "session-1");
            service.playerDisconnected("alice", "session-1");
            service.playerConnected("alice", "session-2");

            verify(gameService, after(180).never()).handlePlayerOfflineTimeout("alice");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void timeoutStartsOnlyAfterLastSessionDisconnects() {
        GameService gameService = mock(GameService.class);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PlayerConnectionService service = new PlayerConnectionService(gameService, scheduler, 50L);

        try {
            service.playerConnected("alice", "session-1");
            service.playerConnected("alice", "session-2");
            service.playerDisconnected("alice", "session-1");

            verify(gameService, after(100).never()).handlePlayerOfflineTimeout("alice");

            service.playerDisconnected("alice", "session-2");

            verify(gameService, timeout(500)).handlePlayerOfflineTimeout("alice");
        } finally {
            service.shutdown();
        }
    }
}
