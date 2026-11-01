package com.yandu.translation.infrastructure.http;

import com.yandu.translation.application.MachineTranslationProvider;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Selects one server-side machine translation adapter from deployment configuration. */
@Configuration
public class MachineTranslationProviderConfiguration {

    @Bean
    MachineTranslationProvider machineTranslationProvider(
            RestClient.Builder builder,
            @Value("${yandu.machine-translation.provider:libretranslate}") String provider,
            @Value("${yandu.machine-translation.base-url:}") String baseUrl,
            @Value("${yandu.machine-translation.api-key:}") String apiKey,
            @Value("${yandu.machine-translation.model:}") String model,
            @Value("${yandu.machine-translation.timeout-ms:30000}") int timeoutMs) {
        return switch (provider.trim().toLowerCase(Locale.ROOT)) {
            case "libretranslate" -> new LibreTranslateProvider(builder.clone(), baseUrl, apiKey, timeoutMs);
            case "openai-compatible" -> new OpenAiCompatibleProvider(
                    builder.clone(), baseUrl, apiKey, model, timeoutMs);
            default -> throw new IllegalArgumentException(
                    "不支持的 YANDU_MT_PROVIDER；可选值为 libretranslate、openai-compatible。");
        };
    }
}
