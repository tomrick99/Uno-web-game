package com.uno.websocket;

import com.uno.session.SessionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ActiveWebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(ActiveWebSocketSessionRegistry.class);
    private final Map<String, Set<WebSocketSession>> sessionsByHttpSessionId = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                register(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                unregister(session);
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    void register(WebSocketSession session) {
        Object httpSessionId = session.getAttributes().get(SessionPolicy.HTTP_SESSION_ID_ATTRIBUTE);
        if (httpSessionId == null) return;
        sessionsByHttpSessionId
                .computeIfAbsent(String.valueOf(httpSessionId), ignored -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    void unregister(WebSocketSession session) {
        Object httpSessionId = session.getAttributes().get(SessionPolicy.HTTP_SESSION_ID_ATTRIBUTE);
        if (httpSessionId == null) return;
        String id = String.valueOf(httpSessionId);
        Set<WebSocketSession> sessions = sessionsByHttpSessionId.get(id);
        if (sessions == null) return;
        sessions.remove(session);
        if (sessions.isEmpty()) sessionsByHttpSessionId.remove(id, sessions);
    }

    public void closeForHttpSession(String httpSessionId) {
        Set<WebSocketSession> sessions = sessionsByHttpSessionId.remove(httpSessionId);
        if (sessions == null) return;
        CloseStatus status = new CloseStatus(
                SessionPolicy.REPLACED_WEBSOCKET_CLOSE_CODE,
                SessionPolicy.REPLACED_MESSAGE);
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) session.close(status);
            } catch (IOException error) {
                log.warn("[WS] failed to close replaced session websocket sessionId={}", session.getId(), error);
            }
        }
    }

    int countForHttpSession(String httpSessionId) {
        Set<WebSocketSession> sessions = sessionsByHttpSessionId.get(httpSessionId);
        return sessions == null ? 0 : sessions.size();
    }
}
