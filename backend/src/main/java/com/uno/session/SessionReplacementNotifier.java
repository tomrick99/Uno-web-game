package com.uno.session;

import com.uno.websocket.ActiveWebSocketSessionRegistry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SessionReplacementNotifier {

    private static final long DELIVERY_WINDOW_MS = 150L;

    private final SimpMessagingTemplate messagingTemplate;
    private final ActiveWebSocketSessionRegistry webSocketSessions;
    private final TaskScheduler taskScheduler;

    public SessionReplacementNotifier(SimpMessagingTemplate messagingTemplate,
                                      ActiveWebSocketSessionRegistry webSocketSessions,
                                      TaskScheduler taskScheduler) {
        this.messagingTemplate = messagingTemplate;
        this.webSocketSessions = webSocketSessions;
        this.taskScheduler = taskScheduler;
    }

    public void notifyReplacement(ActiveSessionRegistry.ReplacedSession replaced) {
        messagingTemplate.convertAndSendToUser(
                replaced.username(),
                "/queue/session",
                new SessionReplacementEvent(SessionPolicy.REPLACED_EVENT_TYPE, SessionPolicy.REPLACED_MESSAGE));
        taskScheduler.schedule(
                () -> webSocketSessions.closeForHttpSession(replaced.sessionId()),
                Instant.now().plusMillis(DELIVERY_WINDOW_MS));
    }

    public record SessionReplacementEvent(String type, String message) {
    }
}
