package com.yandu.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class PublicReleaseWebSocketConfiguration implements WebSocketConfigurer {

    private final PublicReleaseWebSocketHandler handler;

    public PublicReleaseWebSocketConfiguration(PublicReleaseWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/public/translations/ws")
                .setAllowedOriginPatterns("*");
    }
}
