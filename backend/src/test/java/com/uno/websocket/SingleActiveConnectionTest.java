package com.uno.websocket;

import com.uno.auth.ActiveLoginService;
import com.uno.auth.LoginSessionReplacedEvent;
import com.uno.entity.User;
import com.uno.service.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleActiveConnectionTest {

    @Test
    void replacementNotifiesAndRemovesOldPlayerConnectionBeforeNewConnectionTakesOver() {
        List<Object> loginEvents = new ArrayList<>();
        ActiveLoginService activeLoginService = new ActiveLoginService(loginEvents::add);
        ActiveWebSocketSessionRegistry sessionRegistry =
                new ActiveWebSocketSessionRegistry(activeLoginService);
        GameService gameService = mock(GameService.class);
        GameWebSocketService webSocketService = mock(GameWebSocketService.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        List<Runnable> pendingDisconnectChecks = new ArrayList<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            pendingDisconnectChecks.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        GameConnectionEventListener listener = new GameConnectionEventListener(
                gameService, scheduler, webSocketService, sessionRegistry);

        User alice = new User("alice", "encoded");
        alice.setId(1L);
        MockHttpSession deviceA = new MockHttpSession();
        activeLoginService.activate(alice, deviceA);
        String tokenA = (String) deviceA.getAttribute(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
        listener.registerConnectedSession("ws-a", "alice", 1L, tokenA);
        listener.registerGameSubscription("ws-a");

        MockHttpSession deviceB = new MockHttpSession();
        activeLoginService.activate(alice, deviceB);
        LoginSessionReplacedEvent replacement = (LoginSessionReplacedEvent) loginEvents.get(0);
        listener.onLoginSessionReplaced(replacement);

        verify(webSocketService).sendSessionReplaced("alice", "ws-a");
        assertEquals(List.of(), sessionRegistry.findActiveSessionIds("alice"));

        String tokenB = (String) deviceB.getAttribute(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
        listener.registerConnectedSession("ws-b", "alice", 1L, tokenB);
        listener.registerGameSubscription("ws-b");
        pendingDisconnectChecks.forEach(Runnable::run);

        assertEquals(List.of("ws-b"), sessionRegistry.findActiveSessionIds("alice"));
        verify(gameService, never()).playerDisconnected(anyString());
    }
}
