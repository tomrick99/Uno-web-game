package com.uno.auth;

import com.uno.entity.User;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveLoginService {

    public static final String LOGIN_TOKEN_ATTRIBUTE = "activeLoginToken";
    public static final String SESSION_REPLACED_MESSAGE =
            "This account was signed in on another device. You have been signed out.";

    private final ApplicationEventPublisher eventPublisher;
    private final Map<Long, ActiveLogin> activeLoginByUserId = new ConcurrentHashMap<>();

    public ActiveLoginService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String activate(User user, HttpSession session) {
        Long previousUserId = attributeAsLong(session, "userId");
        String previousToken = attributeAsString(session, LOGIN_TOKEN_ATTRIBUTE);
        if (previousUserId != null && !previousUserId.equals(user.getId()) && previousToken != null) {
            ActiveLogin previousForSession = activeLoginByUserId.get(previousUserId);
            if (previousForSession != null && previousToken.equals(previousForSession.loginToken())) {
                activeLoginByUserId.remove(previousUserId, previousForSession);
                publishReplacement(previousForSession);
            }
        }

        String loginToken = UUID.randomUUID().toString();
        ActiveLogin next = new ActiveLogin(user.getId(), user.getUsername(), loginToken, session.getId());
        session.setAttribute("userId", user.getId());
        session.setAttribute("username", user.getUsername());
        session.setAttribute(LOGIN_TOKEN_ATTRIBUTE, loginToken);

        ActiveLogin replaced = activeLoginByUserId.put(user.getId(), next);
        if (replaced != null && !replaced.loginToken().equals(loginToken)) {
            publishReplacement(replaced);
        }
        return loginToken;
    }

    public boolean isActive(HttpSession session) {
        if (session == null) {
            return false;
        }
        return isActive(
                attributeAsLong(session, "userId"),
                attributeAsString(session, LOGIN_TOKEN_ATTRIBUTE)
        );
    }

    public boolean isActive(Long userId, String loginToken) {
        if (userId == null || loginToken == null || loginToken.isBlank()) {
            return false;
        }
        ActiveLogin active = activeLoginByUserId.get(userId);
        return active != null && loginToken.equals(active.loginToken());
    }

    public boolean isReplaced(HttpSession session) {
        if (session == null) {
            return false;
        }
        Long userId = attributeAsLong(session, "userId");
        String loginToken = attributeAsString(session, LOGIN_TOKEN_ATTRIBUTE);
        if (userId == null || loginToken == null) {
            return false;
        }
        return !isActive(userId, loginToken);
    }

    public void deactivate(HttpSession session) {
        if (session == null) {
            return;
        }
        Long userId = attributeAsLong(session, "userId");
        String loginToken = attributeAsString(session, LOGIN_TOKEN_ATTRIBUTE);
        if (userId == null || loginToken == null) {
            return;
        }
        ActiveLogin active = activeLoginByUserId.get(userId);
        if (active != null && loginToken.equals(active.loginToken())) {
            activeLoginByUserId.remove(userId, active);
        }
    }

    private void publishReplacement(ActiveLogin replaced) {
        eventPublisher.publishEvent(new LoginSessionReplacedEvent(
                replaced.userId(),
                replaced.username(),
                replaced.loginToken(),
                replaced.httpSessionId()
        ));
    }

    private Long attributeAsLong(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Long longValue ? longValue : null;
    }

    private String attributeAsString(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof String stringValue ? stringValue : null;
    }

    private record ActiveLogin(Long userId, String username, String loginToken, String httpSessionId) {
    }
}
