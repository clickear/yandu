package com.yandu.translation.provider.libretranslate;

import com.yandu.translation.contract.MachineTranslationProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yandu.machine-translation", name = "provider", havingValue = "libretranslate")
public class LibreTranslateAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MachineTranslationProvider.class)
    MachineTranslationProvider libreTranslateProvider(
            RestClient.Builder builder,
            @Value("${yandu.machine-translation.base-url:}") String baseUrl,
            @Value("${yandu.machine-translation.api-key:}") String apiKey,
            @Value("${yandu.machine-translation.timeout-ms:30000}") int timeoutMs) {
        return new LibreTranslateProvider(builder.clone(), baseUrl, apiKey, timeoutMs);
    }
}
