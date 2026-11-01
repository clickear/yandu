package com.yandu.consumer.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.consumer.TranslationSyncProperties;
import com.yandu.consumer.TranslationSyncTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/** Optional JDK WebSocket client; it adds no Netty dependency to consumer applications. */
public class WebSocketSyncTransport implements TranslationSyncTransport {

    private static final Log log = LogFactory.getLog(WebSocketSyncTransport.class);

    @Override public String mode() { return "websocket"; }

    @Override
    public AutoCloseable connect(TranslationSyncProperties properties, ObjectMapper json,
                                 Supplier<String> currentChecksum, Runnable onChange) {
        return new Connection(properties, json, currentChecksum, onChange);
    }

    private static final class Connection implements AutoCloseable {
        private final TranslationSyncProperties properties;
        private final ObjectMapper json;
        private final Supplier<String> currentChecksum;
        private final Runnable onChange;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "yandu-websocket-translation-sync");
            thread.setDaemon(true);
            return thread;
        });
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        private volatile boolean running = true;
        private volatile WebSocket socket;

        Connection(TranslationSyncProperties properties, ObjectMapper json,
                   Supplier<String> currentChecksum, Runnable onChange) {
            this.properties = properties;
            this.json = json;
            this.currentChecksum = currentChecksum;
            this.onChange = onChange;
            executor.execute(this::reconnectUntilStopped);
        }

        private void reconnectUntilStopped() {
            while (running) {
                var disconnected = new CountDownLatch(1);
                var fragments = new StringBuilder();
                var listener = new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        try { webSocket.sendText(json.writeValueAsString(subscription()), true); }
                        catch (Exception failure) { webSocket.abort(); disconnected.countDown(); }
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        synchronized (fragments) {
                            fragments.append(data);
                            if (last) {
                                handleMessage(fragments.toString());
                                fragments.setLength(0);
                            }
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        disconnected.countDown();
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override public void onError(WebSocket webSocket, Throwable error) {
                        disconnected.countDown();
                    }
                };
                try {
                    socket = client.newWebSocketBuilder().buildAsync(websocketUri(), listener).join();
                    disconnected.await();
                } catch (Exception failure) {
                    if (running) log.warn("YANDU WebSocket sync disconnected; reconnecting ("
                            + failure.getClass().getSimpleName() + ")");
                } finally {
                    if (socket != null) { socket.abort(); socket = null; }
                }
                if (running) {
                    try { Thread.sleep(1_000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }

        private void handleMessage(String payload) {
            try {
                var type = json.readTree(payload).path("type").asText();
                if ("release-changed".equals(type) || "heartbeat".equals(type)) {
                    CompletableFuture.runAsync(onChange);
                }
            } catch (Exception invalidMessage) {
                log.warn("YANDU WebSocket sync received an invalid notification");
            }
        }

        private Map<String, Object> subscription() {
            var request = new HashMap<String, Object>();
            request.put("type", "subscribe");
            request.put("projectId", properties.getProjectId());
            request.put("locale", properties.configuredLocales().get(0));
            request.put("format", properties.getFormat());
            request.put("afterChecksum", currentChecksum.get());
            return request;
        }

        private URI websocketUri() {
            if (properties.getWebsocketUrl() != null && !properties.getWebsocketUrl().isBlank()) {
                return URI.create(properties.getWebsocketUrl());
            }
            var base = URI.create(properties.getApiBaseUrl());
            var scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
            var port = base.getPort() < 0 ? "" : ":" + base.getPort();
            return URI.create(scheme + "://" + base.getHost() + port + "/api/v1/public/translations/ws");
        }

        @Override
        public void close() {
            running = false;
            if (socket != null) socket.abort();
            executor.shutdownNow();
        }
    }
}
