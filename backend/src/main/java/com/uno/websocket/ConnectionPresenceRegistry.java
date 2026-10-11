package com.uno.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConnectionPresenceRegistry {

    private final ConcurrentHashMap<String, Set<String>> sessionsByUsername = new ConcurrentHashMap<>();

    public boolean connected(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        AtomicBoolean wasOffline = new AtomicBoolean(false);
        sessionsByUsername.compute(username, (ignored, sessions) -> {
            Set<String> activeSessions = sessions != null ? sessions : ConcurrentHashMap.newKeySet();
            wasOffline.set(activeSessions.isEmpty());
            activeSessions.add(sessionId);
            return activeSessions;
        });
        return wasOffline.get();
    }

    public boolean disconnected(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        AtomicBoolean nowOffline = new AtomicBoolean(false);
        sessionsByUsername.computeIfPresent(username, (ignored, sessions) -> {
            sessions.remove(sessionId);
            if (!sessions.isEmpty()) {
                return sessions;
            }
            nowOffline.set(true);
            return null;
        });
        return nowOffline.get();
    }

    public boolean isConnected(String username) {
        Set<String> sessions = sessionsByUsername.get(username);
        if (sessions == null) {
            return false;
        }
        synchronized (sessions) {
            return !sessions.isEmpty();
        }
    }
}
