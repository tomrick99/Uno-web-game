package com.uno.websocket;

import com.uno.session.ActiveLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ActiveWebSocketRegistry {

    private static final Logger log = LoggerFactory.getLogger(ActiveWebSocketRegistry.class);

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, String> loginTokenBySessionId = new ConcurrentHashMap<>();

    public void register(WebSocketSession session) {
        Object loginToken = session.getAttributes().get(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
        if (!(loginToken instanceof String token) || token.isBlank()) {
            return;
        }
        sessionsById.put(session.getId(), session);
        loginTokenBySessionId.put(session.getId(), token);
    }

    public void unregister(String sessionId) {
        sessionsById.remove(sessionId);
        loginTokenBySessionId.remove(sessionId);
    }

    public void closeLogin(String loginToken, CloseStatus status) {
        loginTokenBySessionId.forEach((sessionId, token) -> {
            if (loginToken.equals(token)) {
                closeSession(sessionId, status);
            }
        });
    }

    public Set<String> sessionIdsForLogin(String loginToken) {
        Set<String> sessionIds = new LinkedHashSet<>();
        loginTokenBySessionId.forEach((sessionId, token) -> {
            if (loginToken.equals(token)) {
                sessionIds.add(sessionId);
            }
        });
        return sessionIds;
    }

    public String loginTokenForSession(String sessionId) {
        return loginTokenBySessionId.get(sessionId);
    }

    public void closeSession(String sessionId, CloseStatus status) {
        WebSocketSession session = sessionsById.remove(sessionId);
        loginTokenBySessionId.remove(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException e) {
            log.warn("[WS] failed to close superseded session sessionId={} error={}",
                    sessionId, e.getMessage());
        }
    }
}
