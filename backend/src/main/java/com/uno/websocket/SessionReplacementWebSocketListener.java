package com.uno.websocket;

import com.uno.session.ActiveLoginService;
import com.uno.session.LoginSessionReplacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class SessionReplacementWebSocketListener {

    static final long REPLACEMENT_NOTICE_GRACE_MS = 250L;

    private static final Logger log = LoggerFactory.getLogger(SessionReplacementWebSocketListener.class);

    private final GameConnectionEventListener connectionListener;
    private final GameWebSocketService webSocketService;
    private final ActiveWebSocketRegistry webSocketRegistry;
    private final TaskScheduler taskScheduler;

    public SessionReplacementWebSocketListener(GameConnectionEventListener connectionListener,
                                               GameWebSocketService webSocketService,
                                               ActiveWebSocketRegistry webSocketRegistry,
                                               TaskScheduler taskScheduler) {
        this.connectionListener = connectionListener;
        this.webSocketService = webSocketService;
        this.webSocketRegistry = webSocketRegistry;
        this.taskScheduler = taskScheduler;
    }

    @EventListener
    public void onLoginSessionReplaced(LoginSessionReplacedEvent event) {
        Set<String> replacedWebSocketIds = new LinkedHashSet<>(
                webSocketRegistry.sessionIdsForLogin(event.replacedLoginToken()));
        replacedWebSocketIds.addAll(connectionListener.revokeLoginToken(event.replacedLoginToken()));
        connectionListener.revokeSessions(replacedWebSocketIds);
        replacedWebSocketIds.forEach(sessionId -> {
            try {
                webSocketService.sendSessionReplaced(event.username(), sessionId);
            } catch (RuntimeException notificationFailure) {
                log.warn("[AUTH] failed to deliver replacement notice user={} wsSessionId={} error={}",
                        event.username(), sessionId, notificationFailure.getMessage());
            }
        });

        taskScheduler.schedule(
                () -> webSocketRegistry.closeLogin(
                        event.replacedLoginToken(),
                        CloseStatus.POLICY_VIOLATION.withReason("login replaced")
                ),
                Instant.now().plusMillis(REPLACEMENT_NOTICE_GRACE_MS)
        );
        log.info("[AUTH] replaced login user={} oldConnections={}",
                event.username(), replacedWebSocketIds.size());
    }
}
