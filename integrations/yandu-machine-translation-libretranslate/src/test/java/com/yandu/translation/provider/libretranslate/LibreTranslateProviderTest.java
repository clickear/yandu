package com.yandu.translation.provider.libretranslate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class LibreTranslateProviderTest {
    @Test
    void sendsLanguageCodesAndCredentialAndParsesResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new AtomicReference<String>();
        server.createContext("/translate", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "{\"translatedText\":\"Hello\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var provider = new LibreTranslateProvider(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", 5000);
            assertEquals("Hello", provider.translate("你好", "zh-CN", "en-US"));
            assertTrue(body.get().contains("\"source\":\"zh\""));
            assertTrue(body.get().contains("\"target\":\"en\""));
            assertTrue(body.get().contains("\"api_key\":\"test-secret\""));
        } finally { server.stop(0); }
    }
}
