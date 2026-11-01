package com.yandu.consumer.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.consumer.TranslationSyncProperties;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketSyncTransportTest {

    @Test
    void connectsAndRefreshesOnPublishedReleaseMessage() throws Exception {
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            var properties = new TranslationSyncProperties();
            properties.setApiBaseUrl("http://127.0.0.1:8080");
            properties.setWebsocketUrl("ws://127.0.0.1:" + server.getLocalPort() + "/sync");
            properties.setProjectId(1001L);
            properties.setLocale("fr-FR");
            var changed = new CountDownLatch(1);
            var connection = new WebSocketSyncTransport().connect(properties, new ObjectMapper(), () -> null,
                    changed::countDown);
            try (var socket = server.accept()) {
                socket.setSoTimeout(5_000);
                var request = readHttpHeaders(socket.getInputStream());
                var key = request.lines().filter(line -> line.regionMatches(true, 0,
                        "Sec-WebSocket-Key:", 0, "Sec-WebSocket-Key:".length()))
                        .map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElseThrow();
                var accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.ISO_8859_1)));
                var output = socket.getOutputStream();
                output.write(("HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                output.flush();

                var subscription = new ObjectMapper().readTree(readClientTextFrame(socket.getInputStream()));
                assertThat(subscription.path("type").asText()).isEqualTo("subscribe");
                assertThat(subscription.path("projectId").asLong()).isEqualTo(1001L);
                sendServerTextFrame(output, "{\"type\":\"release-changed\"}");
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                connection.close();
            }
        }
    }

    private static String readHttpHeaders(InputStream input) throws Exception {
        var bytes = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int value = input.read();
            if (value < 0) throw new IllegalStateException("WebSocket handshake closed early");
            bytes.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> state;
            };
        }
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    private static String readClientTextFrame(InputStream input) throws Exception {
        int first = input.read();
        int second = input.read();
        if (first != 0x81 || second < 0) throw new IllegalStateException("Expected a WebSocket text frame");
        long length = second & 0x7f;
        if (length == 126) length = (input.read() << 8) | input.read();
        else if (length == 127) throw new IllegalStateException("Unexpected oversized subscription frame");
        var mask = input.readNBytes(4);
        var payload = input.readNBytes((int) length);
        if (mask.length != 4 || payload.length != length) throw new IllegalStateException("Incomplete frame");
        for (int index = 0; index < payload.length; index++) payload[index] ^= mask[index % 4];
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void sendServerTextFrame(OutputStream output, String payload) throws Exception {
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        output.write(0x81);
        if (bytes.length < 126) output.write(bytes.length);
        else { output.write(126); output.write(bytes.length >>> 8); output.write(bytes.length); }
        output.write(bytes);
        output.flush();
    }
}
