package com.uno.websocket;

import com.uno.session.ActiveSessionRegistry;
import com.uno.session.SessionPolicy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;

@Component
public class ActiveSessionChannelInterceptor implements ChannelInterceptor {

    private final ActiveSessionRegistry activeSessions;

    public ActiveSessionChannelInterceptor(ActiveSessionRegistry activeSessions) {
        this.activeSessions = activeSessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null
                || StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            return message;
        }

        Principal user = accessor.getUser();
        Map<String, Object> attributes = accessor.getSessionAttributes();
        Object httpSessionId = attributes == null
                ? null
                : attributes.get(SessionPolicy.HTTP_SESSION_ID_ATTRIBUTE);
        if (user == null || httpSessionId == null
                || !activeSessions.isActive(user.getName(), String.valueOf(httpSessionId))) {
            throw new AccessDeniedException(SessionPolicy.REPLACED_MESSAGE);
        }
        return message;
    }
}
