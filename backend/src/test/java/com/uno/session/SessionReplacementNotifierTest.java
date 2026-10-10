package com.uno.session;

import com.uno.websocket.ActiveWebSocketSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionReplacementNotifierTest {

    @Test
    void oldUserDestinationReceivesReasonBeforeItsSocketsAreClosed() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ActiveWebSocketSessionRegistry webSockets = mock(ActiveWebSocketSessionRegistry.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        AtomicReference<Runnable> closeTask = new AtomicReference<>();
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            closeTask.set(invocation.getArgument(0));
            return null;
        });
        SessionReplacementNotifier notifier = new SessionReplacementNotifier(messaging, webSockets, scheduler);

        notifier.notifyReplacement(new ActiveSessionRegistry.ReplacedSession("alice", "old-http-session"));

        verify(messaging).convertAndSendToUser(
                eq("alice"),
                eq("/queue/session"),
                eq(new SessionReplacementNotifier.SessionReplacementEvent(
                        SessionPolicy.REPLACED_EVENT_TYPE, SessionPolicy.REPLACED_MESSAGE)));
        assertNotNull(closeTask.get());
        closeTask.get().run();
        verify(webSockets).closeForHttpSession("old-http-session");
        assertEquals("This account was signed in on another device. You have been signed out.",
                SessionPolicy.REPLACED_MESSAGE);
    }
}
