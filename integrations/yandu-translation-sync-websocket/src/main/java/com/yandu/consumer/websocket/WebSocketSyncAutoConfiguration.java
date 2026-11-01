package com.yandu.consumer.websocket;

import com.yandu.consumer.TranslationSyncTransport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yandu.translation-sync", name = "mode", havingValue = "websocket")
public class WebSocketSyncAutoConfiguration {

    @Bean
    TranslationSyncTransport yanduWebSocketSyncTransport() {
        return new WebSocketSyncTransport();
    }
}
