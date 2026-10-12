package com.uno.websocket;

import com.uno.service.GameService;
import com.uno.session.LoginSessionReplacedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionReplacementWebSocketListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void replacementNotifiesRevokesAndClosesOnlyTheOldLoginConnections() {
        GameService gameService = mock(GameService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ActiveWebSocketRegistry webSocketRegistry = mock(ActiveWebSocketRegistry.class);
        GameWebSocketService webSocketService = mock(GameWebSocketService.class);
        List<Runnable> scheduled = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            scheduled.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        when(webSocketRegistry.sessionIdsForLogin("old-login"))
                .thenReturn(Set.of("old-game-ws"));
        GameConnectionEventListener connections = new GameConnectionEventListener(gameService, scheduler);
        connections.registerConnectedSession("old-game-ws", "alice");
        connections.registerGameSubscription("old-game-ws");
        SessionReplacementWebSocketListener listener = new SessionReplacementWebSocketListener(
                connections, webSocketService, webSocketRegistry, scheduler);

        listener.onLoginSessionReplaced(new LoginSessionReplacedEvent(
                1L, "alice", "old-login", "new-login"));

        assertEquals(0, connections.activeGameConnectionCount("alice"));
        verify(webSocketService).sendSessionReplaced("alice", "old-game-ws");
        scheduled.forEach(Runnable::run);
        verify(webSocketRegistry).closeLogin(
                "old-login",
                CloseStatus.POLICY_VIOLATION.withReason("login replaced")
        );
    }
}
