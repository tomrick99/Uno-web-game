package com.uno.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 配置 — 启用 STOMP over SockJS
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${spring.websocket.allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}")
    private String[] allowedOriginPatterns;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 客户端订阅 /topic 前缀，服务器广播到 /topic/xxx
        config.enableSimpleBroker("/topic");
        // 客户端发送消息的地址前缀
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 端点，前端通过 /api/ws 连接
        registry.addEndpoint("/api/ws")
                .setAllowedOriginPatterns(allowedOriginPatterns)
                .withSockJS();
    }
}
