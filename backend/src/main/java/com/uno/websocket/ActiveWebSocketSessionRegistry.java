package com.uno.websocket;

import com.uno.auth.ActiveLoginService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ActiveWebSocketSessionRegistry {

    private final ActiveLoginService activeLoginService;
    private final ConcurrentMap<String, Connection> connectionBySessionId = new ConcurrentHashMap<>();

    public ActiveWebSocketSessionRegistry(ActiveLoginService activeLoginService) {
        this.activeLoginService = activeLoginService;
    }

    public void register(String sessionId, String username, Long userId, String loginToken) {
        connectionBySessionId.put(sessionId, new Connection(sessionId, username, userId, loginToken));
    }

    public Connection remove(String sessionId) {
        return connectionBySessionId.remove(sessionId);
    }

    public List<Connection> findByLoginToken(String loginToken) {
        return connectionBySessionId.values().stream()
                .filter(connection -> loginToken != null && loginToken.equals(connection.loginToken()))
                .toList();
    }

    public List<String> findActiveSessionIds(String username) {
        return connectionBySessionId.values().stream()
                .filter(connection -> username.equals(connection.username()))
                .filter(connection -> activeLoginService.isActive(connection.userId(), connection.loginToken()))
                .map(Connection::sessionId)
                .toList();
    }

    public record Connection(String sessionId, String username, Long userId, String loginToken) {
    }
}
