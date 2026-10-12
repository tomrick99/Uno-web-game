package com.uno.session;

import com.uno.entity.User;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveLoginService implements HttpSessionListener {

    public static final String USER_ID_ATTRIBUTE = "userId";
    public static final String USERNAME_ATTRIBUTE = "username";
    public static final String LOGIN_TOKEN_ATTRIBUTE = "activeLoginToken";
    public static final String INVALIDATION_REASON_ATTRIBUTE = "sessionInvalidationReason";
    public static final String REPLACED_MESSAGE =
            "This account was signed in on another device. You have been signed out.";

    private final Map<Long, ActiveLogin> activeLogins = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;

    public ActiveLoginService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public ActiveLogin activate(User user, HttpSession session) {
        Long userId = user.getId();
        String currentSessionId = session.getId();
        ActiveLogin alreadyActive = activeLogins.get(userId);
        if (alreadyActive != null && alreadyActive.httpSessionId().equals(currentSessionId)) {
            setAuthenticatedAttributes(session, user, alreadyActive.loginToken());
            return alreadyActive;
        }

        removePreviousAccountBinding(session);

        String loginToken = UUID.randomUUID().toString();
        setAuthenticatedAttributes(session, user, loginToken);
        ActiveLogin replacement = new ActiveLogin(
                userId,
                user.getUsername(),
                loginToken,
                currentSessionId,
                session
        );
        ActiveLogin replaced = activeLogins.put(userId, replacement);
        if (replaced != null && !replaced.httpSessionId().equals(currentSessionId)) {
            markReplaced(replaced.session());
            eventPublisher.publishEvent(new LoginSessionReplacedEvent(
                    userId,
                    user.getUsername(),
                    replaced.loginToken(),
                    replacement.loginToken()
            ));
        }
        return replacement;
    }

    public boolean isActive(HttpSession session) {
        Object userId = getAttribute(session, USER_ID_ATTRIBUTE);
        Object loginToken = getAttribute(session, LOGIN_TOKEN_ATTRIBUTE);
        return userId instanceof Long id
                && loginToken instanceof String token
                && isActive(id, token);
    }

    public boolean isActive(Long userId, String loginToken) {
        if (userId == null || loginToken == null || loginToken.isBlank()) {
            return false;
        }
        ActiveLogin activeLogin = activeLogins.get(userId);
        return activeLogin != null && activeLogin.loginToken().equals(loginToken);
    }

    public String getInactiveReason(HttpSession session) {
        Object reason = getAttribute(session, INVALIDATION_REASON_ATTRIBUTE);
        return reason instanceof String text && !text.isBlank() ? text : "Please log in first";
    }

    public void logout(HttpSession session) {
        removeIfCurrent(session);
        session.invalidate();
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        removeIfCurrent(event.getSession());
    }

    int activeLoginCount() {
        return activeLogins.size();
    }

    private void removePreviousAccountBinding(HttpSession session) {
        Object previousUserId = getAttribute(session, USER_ID_ATTRIBUTE);
        Object previousToken = getAttribute(session, LOGIN_TOKEN_ATTRIBUTE);
        if (previousUserId instanceof Long id && previousToken instanceof String token) {
            activeLogins.computeIfPresent(id, (ignored, active) ->
                    active.loginToken().equals(token) ? null : active);
        }
    }

    private void removeIfCurrent(HttpSession session) {
        removePreviousAccountBinding(session);
    }

    private void setAuthenticatedAttributes(HttpSession session, User user, String loginToken) {
        session.setAttribute(USER_ID_ATTRIBUTE, user.getId());
        session.setAttribute(USERNAME_ATTRIBUTE, user.getUsername());
        session.setAttribute(LOGIN_TOKEN_ATTRIBUTE, loginToken);
        session.removeAttribute(INVALIDATION_REASON_ATTRIBUTE);
    }

    private void markReplaced(HttpSession session) {
        try {
            session.setAttribute(INVALIDATION_REASON_ATTRIBUTE, REPLACED_MESSAGE);
        } catch (IllegalStateException ignored) {
            // The container may have expired the old HTTP session between login attempts.
        }
    }

    private Object getAttribute(HttpSession session, String name) {
        try {
            return session.getAttribute(name);
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    public record ActiveLogin(
            Long userId,
            String username,
            String loginToken,
            String httpSessionId,
            HttpSession session
    ) {
    }
}
