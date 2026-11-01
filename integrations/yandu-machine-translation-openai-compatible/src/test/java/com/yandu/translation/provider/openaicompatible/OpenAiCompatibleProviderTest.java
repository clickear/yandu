package com.yandu.translation.provider.openaicompatible;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleProviderTest {
    @Test
    void sendsModelPromptAndBearerCredential() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = "{\"choices\":[{\"message\":{\"content\":\"Hello {name}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var provider = new OpenAiCompatibleProvider(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "server-secret", "translation-model", 5000);
            assertEquals("Hello {name}", provider.translate("你好 {name}", "zh-CN", "en-US"));
            assertEquals("Bearer server-secret", authorization.get());
            assertTrue(body.get().contains("\"model\":\"translation-model\""));
            assertTrue(body.get().contains("\"content\":\"你好 {name}\""));
            assertTrue(body.get().contains("zh-CN"));
            assertTrue(body.get().contains("en-US"));
        } finally { server.stop(0); }
    }
}
