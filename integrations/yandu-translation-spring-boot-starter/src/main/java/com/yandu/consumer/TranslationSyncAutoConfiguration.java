package com.yandu.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.MessageSource;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.ObjectProvider;

@AutoConfiguration(before = MessageSourceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "yandu.translation-sync", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TranslationSyncProperties.class)
public class TranslationSyncAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TranslationCatalog yanduTranslationCatalog() {
        return new TranslationCatalog();
    }

    @Bean
    @ConditionalOnMissingBean(YanduMessageSource.class)
    YanduMessageSource yanduTranslationMessageSource(TranslationCatalog catalog,
                                                       TranslationSyncProperties properties) {
        if (properties.configuredLocales().isEmpty()) {
            throw new IllegalArgumentException("yandu.translation-sync.locales is required when sync is enabled");
        }
        return new YanduMessageSource(catalog, properties.configuredLocales());
    }

    @Bean(name = "messageSource")
    @ConditionalOnMissingBean(name = "messageSource")
    MessageSource messageSource(YanduMessageSource yanduTranslationMessageSource) {
        return yanduTranslationMessageSource;
    }

    @Bean
    TranslationSnapshotPoller yanduTranslationSnapshotPoller(TranslationSyncProperties properties,
                                                               ObjectMapper objectMapper,
                                                               TranslationCatalog catalog,
                                                               ObjectProvider<TranslationSyncTransport> transports) {
        if (properties.getApiBaseUrl() == null || properties.getApiBaseUrl().isBlank()) {
            throw new IllegalArgumentException("yandu.translation-sync.api-base-url is required when sync is enabled");
        }
        var client = RestClient.builder().baseUrl(properties.getApiBaseUrl()).build();
        return new TranslationSnapshotPoller(properties, client, objectMapper, catalog,
                transports.orderedStream().toList());
    }
}
