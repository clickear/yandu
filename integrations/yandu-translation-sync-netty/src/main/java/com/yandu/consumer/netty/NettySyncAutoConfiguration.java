package com.yandu.consumer.netty;

import com.yandu.consumer.TranslationSyncTransport;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yandu.translation-sync", name = "mode", havingValue = "netty")
public class NettySyncAutoConfiguration {

    @Bean
    TranslationSyncTransport yanduNettySyncTransport() {
        return new NettySyncTransport();
    }
}
