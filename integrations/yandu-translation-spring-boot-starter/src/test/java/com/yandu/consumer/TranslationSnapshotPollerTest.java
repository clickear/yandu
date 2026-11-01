package com.yandu.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TranslationSnapshotPollerTest {

    private static final String BODY = "{\"welcome\":\"Bonjour\"}";

    @Test
    void publishesVerifiedSnapshotAndKeepsLastGoodSnapshotWhenDownloadIsInvalid() throws Exception {
        var properties = properties();
        var builder = RestClient.builder().baseUrl("http://yandu.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var catalog = new TranslationCatalog();
        var poller = new TranslationSnapshotPoller(properties, builder.build(), new ObjectMapper(), catalog);
        server.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest?format=json-flat-v1"))
                .andRespond(withSuccess(metadata(sha256(BODY)), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest/content?format=json-flat-v1"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        poller.refreshNow();

        assertThat(catalog.require("welcome")).isEqualTo("Bonjour");
        assertThat(catalog.version()).isEqualTo(sha256(BODY));
        assertThat(catalog.snapshot()).containsOnlyKeys("welcome");
        server.verify();

        var invalidBuilder = RestClient.builder().baseUrl("http://yandu.test");
        var invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        var invalidPoller = new TranslationSnapshotPoller(properties, invalidBuilder.build(),
                new ObjectMapper(), catalog);
        invalidServer.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest?format=json-flat-v1"))
                .andRespond(withSuccess(metadata("different-checksum"), MediaType.APPLICATION_JSON));
        invalidServer.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest/content?format=json-flat-v1"))
                .andRespond(withSuccess("{\"welcome\":\"Salut\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(invalidPoller::refreshNow).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.require("welcome")).isEqualTo("Bonjour");
        invalidServer.verify();
    }

    @Test
    void parsesJavaPropertiesSnapshot() throws Exception {
        var properties = properties();
        properties.setFormat("java-properties-v1");
        var builder = RestClient.builder().baseUrl("http://yandu.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var catalog = new TranslationCatalog();
        var poller = new TranslationSnapshotPoller(properties, builder.build(), new ObjectMapper(), catalog);
        var content = "welcome=Bonjour\\u0021\n";
        server.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest?format=java-properties-v1"))
                .andRespond(withSuccess(metadata(sha256(content)), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://yandu.test/api/v1/public/projects/1001/translations/fr-FR/latest/content?format=java-properties-v1"))
                .andRespond(withSuccess(content, MediaType.TEXT_PLAIN));

        poller.refreshNow();

        assertThat(catalog.require("welcome")).isEqualTo("Bonjour!");
        server.verify();
    }

    private static TranslationSyncProperties properties() {
        var properties = new TranslationSyncProperties();
        properties.setProjectId(1001L);
        properties.setLocale("fr-FR");
        return properties;
    }

    private static String metadata(String checksum) {
        return """
                {"id":"42","projectId":"1001","locale":"fr-FR","format":"json-flat-v1",
                 "version":7,"checksumSha256":"%s","publishedAt":"2026-10-31T00:00:00Z"}
                """.formatted(checksum);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
