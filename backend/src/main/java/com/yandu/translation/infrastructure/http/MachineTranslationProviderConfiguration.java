package com.yandu.translation.infrastructure.http;

import com.yandu.translation.contract.MachineTranslationProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the workflow available when no optional provider implementation is installed. */
@Configuration
public class MachineTranslationProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean(MachineTranslationProvider.class)
    MachineTranslationProvider unavailableMachineTranslationProvider() {
        return new MachineTranslationProvider() {
            @Override public boolean isConfigured() { return false; }
            @Override public String translate(String sourceText, String sourceLocale, String targetLocale) {
                throw new MachineTranslationProvider.ProviderFailureException("机器翻译服务尚未配置。");
            }
        };
    }
}
