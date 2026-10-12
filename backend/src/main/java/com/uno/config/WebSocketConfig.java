package com.uno.config;

import com.uno.session.ActiveLoginService;
import com.uno.websocket.ActiveWebSocketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Value("${spring.websocket.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    private final ActiveLoginService activeLoginService;
    private final ActiveWebSocketRegistry webSocketRegistry;

    public WebSocketConfig(ActiveLoginService activeLoginService,
                           ActiveWebSocketRegistry webSocketRegistry) {
        this.activeLoginService = activeLoginService;
        this.webSocketRegistry = webSocketRegistry;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setAllowedOriginPatterns(allowedOriginPatterns)
                .addInterceptors(new HttpSessionHandshakeInterceptor(), new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request,
                                                   ServerHttpResponse response,
                                                   WebSocketHandler wsHandler,
                                                   Map<String, Object> attributes) {
                        Object username = attributes.get(ActiveLoginService.USERNAME_ATTRIBUTE);
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            var session = servletRequest.getServletRequest().getSession(false);
                            if (session != null && session.getAttribute(ActiveLoginService.USER_ID_ATTRIBUTE) != null
                                    && !activeLoginService.isActive(session)) {
                                log.warn("[WS] rejected stale login endpoint=/api/ws sessionId={}", session.getId());
                                return false;
                            }
                            if (username == null && session != null) {
                                username = session.getAttribute(ActiveLoginService.USERNAME_ATTRIBUTE);
                            }
                        }
                        log.info("[WS] connect endpoint=/api/ws user={}", username != null ? username : "anonymous");
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request,
                                               ServerHttpResponse response,
                                               WebSocketHandler wsHandler,
                                               Exception exception) {
                        if (exception != null) {
                            log.warn("[WS] handshake failed endpoint=/api/ws error={}", exception.getMessage());
                        }
                    }
                })
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(ServerHttpRequest request,
                                                      WebSocketHandler wsHandler,
                                                      Map<String, Object> attributes) {
                        Object username = attributes.get(ActiveLoginService.USERNAME_ATTRIBUTE);
                        if (username == null && request instanceof ServletServerHttpRequest servletRequest) {
                            var session = servletRequest.getServletRequest().getSession(false);
                            if (session != null) {
                                username = session.getAttribute(ActiveLoginService.USERNAME_ATTRIBUTE);
                            }
                        }
                        if (username != null) {
                            String principalName = String.valueOf(username);
                            return () -> principalName;
                        }
                        return super.determineUser(request, wsHandler, attributes);
                    }
                })
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler ->
                new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        webSocketRegistry.register(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session,
                                                      org.springframework.web.socket.CloseStatus closeStatus)
                            throws Exception {
                        webSocketRegistry.unregister(session.getId());
                        super.afterConnectionClosed(session, closeStatus);
                    }
                });
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }

                StompCommand command = accessor.getCommand();
                Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                boolean validatesAuthentication = !StompCommand.DISCONNECT.equals(command)
                        && !StompCommand.UNSUBSCRIBE.equals(command);
                if (validatesAuthentication && sessionAttributes != null) {
                    Object userId = sessionAttributes.get(ActiveLoginService.USER_ID_ATTRIBUTE);
                    Object loginToken = sessionAttributes.get(ActiveLoginService.LOGIN_TOKEN_ATTRIBUTE);
                    if (userId instanceof Long id
                            && loginToken instanceof String token
                            && !activeLoginService.isActive(id, token)) {
                        throw new MessageDeliveryException("The authenticated login session is no longer active");
                    }
                }
                Principal user = accessor.getUser();
                String username = user != null ? user.getName() : "anonymous";
                if (StompCommand.CONNECT.equals(command)) {
                    log.info("[WS] stomp connect user={}", username);
                } else if (StompCommand.SUBSCRIBE.equals(command)) {
                    log.info("[WS] subscribe destination={} user={}", accessor.getDestination(), username);
                } else if (StompCommand.DISCONNECT.equals(command)) {
                    log.info("[WS] disconnect user={}", username);
                }
                return message;
            }
        });
    }
}
