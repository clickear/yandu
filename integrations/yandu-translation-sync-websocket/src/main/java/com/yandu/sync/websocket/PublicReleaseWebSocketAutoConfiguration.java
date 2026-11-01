package com.yandu.sync.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.sync.ReleaseSyncService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@AutoConfiguration
@EnableWebSocket
@ConditionalOnProperty(prefix = "yandu.sync.websocket", name = "enabled", havingValue = "true")
public class PublicReleaseWebSocketAutoConfiguration implements WebSocketConfigurer {

    private final PublicReleaseWebSocketHandler handler;

    public PublicReleaseWebSocketAutoConfiguration(ObjectMapper json, ReleaseSyncService releases) {
        this.handler = new PublicReleaseWebSocketHandler(json, releases);
    }

    @Bean
    PublicReleaseWebSocketHandler publicReleaseWebSocketHandler() { return handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/public/translations/ws").setAllowedOriginPatterns("*");
    }
}
