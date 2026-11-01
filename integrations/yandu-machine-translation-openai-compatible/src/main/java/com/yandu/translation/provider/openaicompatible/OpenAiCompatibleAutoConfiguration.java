package com.yandu.translation.provider.openaicompatible;

import com.yandu.translation.contract.MachineTranslationProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yandu.machine-translation", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(MachineTranslationProvider.class)
    MachineTranslationProvider openAiCompatibleProvider(
            RestClient.Builder builder,
            @Value("${yandu.machine-translation.base-url:}") String baseUrl,
            @Value("${yandu.machine-translation.api-key:}") String apiKey,
            @Value("${yandu.machine-translation.model:}") String model,
            @Value("${yandu.machine-translation.timeout-ms:30000}") int timeoutMs) {
        return new OpenAiCompatibleProvider(builder.clone(), baseUrl, apiKey, model, timeoutMs);
    }
}
