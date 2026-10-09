package com.uno.auth;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ActiveLoginChannelInterceptor implements ChannelInterceptor {

    private final ActiveLoginService activeLoginService;

    public ActiveLoginChannelInterceptor(ActiveLoginService activeLoginService) {
        this.activeLoginService = activeLoginService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null
                || StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            return message;
        }

        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null || !(attributes.get("userId") instanceof Long userId)) {
            return message;
        }
        Object tokenValue = attributes.get(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
        String loginToken = tokenValue instanceof String value ? value : null;
        if (!activeLoginService.isActive(userId, loginToken)) {
            throw new MessageDeliveryException(message, ActiveLoginService.SESSION_REPLACED_MESSAGE);
        }
        return message;
    }
}
