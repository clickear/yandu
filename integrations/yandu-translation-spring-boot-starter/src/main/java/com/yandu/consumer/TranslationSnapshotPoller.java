package com.yandu.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.client.RestClient;

/** Polls published release metadata and atomically swaps a verified snapshot. */
public class TranslationSnapshotPoller implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(TranslationSnapshotPoller.class);

    private final TranslationSyncProperties properties;
    private final RestClient client;
    private final ObjectMapper json;
    private final TranslationCatalog catalog;
    private volatile ScheduledExecutorService scheduler;

    public TranslationSnapshotPoller(TranslationSyncProperties properties, RestClient client,
                                     ObjectMapper json, TranslationCatalog catalog) {
        this.properties = properties;
        this.client = client;
        this.json = json;
        this.catalog = catalog;
        if (properties.getProjectId() <= 0 || properties.getLocale() == null || properties.getLocale().isBlank()) {
            throw new IllegalArgumentException("YANDU project-id and locale must be configured when sync is enabled");
        }
        if (properties.getRefreshIntervalMs() < 1_000) {
            throw new IllegalArgumentException("YANDU translation refresh interval must be at least 1000 ms");
        }
        if (!"json-flat-v1".equals(properties.getFormat())
                && !"java-properties-v1".equals(properties.getFormat())) {
            throw new IllegalArgumentException("Unsupported YANDU translation format: " + properties.getFormat());
        }
    }

    public synchronized void refreshNow() {
        var metadata = client.get()
                .uri("/api/v1/public/projects/{projectId}/translations/{locale}/latest?format={format}",
                        properties.getProjectId(), properties.getLocale(), properties.getFormat())
                .retrieve().body(ReleaseMetadata.class);
        if (metadata == null || metadata.checksumSha256() == null) {
            throw new IllegalStateException("YANDU returned incomplete release metadata");
        }
        if (metadata.checksumSha256().equals(catalog.version())) return;
        var content = client.get()
                .uri("/api/v1/public/projects/{projectId}/translations/{locale}/latest/content?format={format}",
                        properties.getProjectId(), properties.getLocale(), properties.getFormat())
                .retrieve().body(String.class);
        if (content == null || !sha256(content).equals(metadata.checksumSha256())) {
            throw new IllegalStateException("YANDU release content did not match its SHA-256 checksum");
        }
        catalog.replace(metadata.checksumSha256(), parse(content));
    }

    private Map<String, String> parse(String content) {
        try {
            if ("json-flat-v1".equals(properties.getFormat())) {
                return json.readValue(content, new TypeReference<Map<String, String>>() { });
            }
            var values = new Properties();
            values.load(new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)));
            return values.stringPropertyNames().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    key -> key, values::getProperty));
        } catch (Exception exception) {
            throw new IllegalStateException("YANDU release content could not be parsed", exception);
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public synchronized void start() {
        if (!isRunning()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "yandu-translation-sync");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::refreshQuietly, 0, properties.getRefreshIntervalMs(),
                    TimeUnit.MILLISECONDS);
        }
    }

    private void refreshQuietly() {
        try {
            refreshNow();
        } catch (RuntimeException exception) {
            // Keep the last verified snapshot; next poll retries automatically.
            log.warn("YANDU translation sync failed; retaining the last verified snapshot ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() { return scheduler != null && !scheduler.isShutdown(); }

    @Override
    public boolean isAutoStartup() { return true; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE; }

    public record ReleaseMetadata(String id, String projectId, String locale, String format,
                                  long version, String checksumSha256, String publishedAt) { }
}
