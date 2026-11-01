package com.yandu.consumer.netty;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.consumer.TranslationSyncProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NettySyncTransportTest {

    @Test
    void connectsWithSubscriptionAndRefreshesWhenServerPublishesChange() throws Exception {
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var properties = new TranslationSyncProperties();
            properties.setApiBaseUrl("http://127.0.0.1:8080");
            properties.setProjectId(1001L);
            properties.setLocale("fr-FR");
            properties.setNettyHost("127.0.0.1");
            properties.setNettyPort(server.getLocalPort());
            var requested = new CountDownLatch(1);
            var changed = new CountDownLatch(1);
            var connection = new NettySyncTransport().connect(properties, new ObjectMapper(), () -> null,
                    changed::countDown);
            try (var socket = server.accept()) {
                socket.setSoTimeout(5_000);
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
                var request = new ObjectMapper().readTree(reader.readLine());
                assertThat(request.path("type").asText()).isEqualTo("subscribe");
                assertThat(request.path("projectId").asLong()).isEqualTo(1001L);
                requested.countDown();
                writer.println("{\"type\":\"release-changed\"}");
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(requested.getCount()).isZero();
            } finally {
                connection.close();
            }
        }
    }
}
