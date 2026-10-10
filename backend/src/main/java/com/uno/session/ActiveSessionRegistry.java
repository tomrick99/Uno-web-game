package com.uno.session;

import com.uno.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ActiveSessionRegistry {

    private static final long REPLACEMENT_MARKER_TTL_MS = Duration.ofHours(24).toMillis();

    private final Object lock = new Object();
    private final Map<Long, ActiveSession> byUserId = new ConcurrentHashMap<>();
    private final Map<String, ActiveSession> byUsername = new ConcurrentHashMap<>();
    private final Map<String, ActiveSession> bySessionId = new ConcurrentHashMap<>();
    private final Map<String, Long> replacedSessionIds = new ConcurrentHashMap<>();

    public Optional<ReplacedSession> activate(User user, HttpSession session) {
        if (user == null || user.getId() == null || user.getUsername() == null) {
            throw new IllegalArgumentException("A persisted user is required");
        }

        String newSessionId = session.getId();
        ActiveSession replaced = null;
        synchronized (lock) {
            cleanupExpiredMarkers();
            ActiveSession current = byUserId.get(user.getId());
            if (current != null && !current.sessionId().equals(newSessionId)) {
                replaced = current;
                removeIfCurrent(current);
                replacedSessionIds.put(current.sessionId(),
                        System.currentTimeMillis() + REPLACEMENT_MARKER_TTL_MS);
            }

            ActiveSession priorIdentity = bySessionId.get(newSessionId);
            if (priorIdentity != null && !priorIdentity.userId().equals(user.getId())) {
                removeIfCurrent(priorIdentity);
            }

            ActiveSession activated = new ActiveSession(
                    user.getId(), user.getUsername(), newSessionId, session);
            byUserId.put(user.getId(), activated);
            byUsername.put(user.getUsername(), activated);
            bySessionId.put(newSessionId, activated);
        }

        if (replaced == null) {
            return Optional.empty();
        }
        invalidateQuietly(replaced.session());
        return Optional.of(new ReplacedSession(replaced.username(), replaced.sessionId()));
    }

    public boolean isActive(Long userId, String sessionId) {
        if (userId == null || sessionId == null) return false;
        return isMatchingAndValid(byUserId.get(userId), sessionId);
    }

    public boolean isActive(String username, String sessionId) {
        if (username == null || sessionId == null) return false;
        return isMatchingAndValid(byUsername.get(username), sessionId);
    }

    public boolean wasReplaced(String sessionId) {
        if (sessionId == null) return false;
        Long expiresAt = replacedSessionIds.get(sessionId);
        if (expiresAt == null) return false;
        if (expiresAt < System.currentTimeMillis()) {
            replacedSessionIds.remove(sessionId, expiresAt);
            return false;
        }
        return true;
    }

    public void deactivate(HttpSession session) {
        if (session == null) return;
        String sessionId = session.getId();
        synchronized (lock) {
            ActiveSession active = bySessionId.get(sessionId);
            if (active != null) removeIfCurrent(active);
        }
        invalidateQuietly(session);
    }

    private boolean isMatchingAndValid(ActiveSession active, String sessionId) {
        if (active == null || !active.sessionId().equals(sessionId)) return false;
        try {
            active.session().getCreationTime();
            return true;
        } catch (IllegalStateException invalidated) {
            synchronized (lock) {
                removeIfCurrent(active);
            }
            return false;
        }
    }

    private void removeIfCurrent(ActiveSession active) {
        byUserId.remove(active.userId(), active);
        byUsername.remove(active.username(), active);
        bySessionId.remove(active.sessionId(), active);
    }

    private void cleanupExpiredMarkers() {
        long now = System.currentTimeMillis();
        replacedSessionIds.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    private void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Already invalidated by the container or an explicit logout.
        }
    }

    private record ActiveSession(Long userId, String username, String sessionId, HttpSession session) {
    }

    public record ReplacedSession(String username, String sessionId) {
    }
}
