package com.yandu.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.List;
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
    private final List<TranslationSyncTransport> transports;
    private volatile ScheduledExecutorService scheduler;
    private volatile List<AutoCloseable> realtimeConnections = List.of();
    private final List<String> locales;

    public TranslationSnapshotPoller(TranslationSyncProperties properties, RestClient client,
                                     ObjectMapper json, TranslationCatalog catalog) {
        this(properties, client, json, catalog, List.of());
    }

    public TranslationSnapshotPoller(TranslationSyncProperties properties, RestClient client,
                                     ObjectMapper json, TranslationCatalog catalog,
                                     List<TranslationSyncTransport> transports) {
        this.properties = properties;
        this.client = client;
        this.json = json;
        this.catalog = catalog;
        this.transports = List.copyOf(transports);
        this.locales = properties.configuredLocales();
        if (properties.getProjectId() <= 0 || locales.isEmpty()) {
            throw new IllegalArgumentException("YANDU project-id and locales must be configured when sync is enabled");
        }
        catalog.setDefaultLocale(locales.get(0));
        if (properties.getRefreshIntervalMs() < 1_000) {
            throw new IllegalArgumentException("YANDU translation refresh interval must be at least 1000 ms");
        }
        if (!"json-flat-v1".equals(properties.getFormat())
                && !"java-properties-v1".equals(properties.getFormat())) {
            throw new IllegalArgumentException("Unsupported YANDU translation format: " + properties.getFormat());
        }
        if (!"polling".equalsIgnoreCase(properties.getMode())
                && !"netty".equalsIgnoreCase(properties.getMode())
                && !"websocket".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalArgumentException("YANDU translation sync mode must be polling, netty or websocket");
        }
    }

    public synchronized void refreshNow() {
        for (var locale : locales) refreshLocale(locale);
    }

    public synchronized void refreshNow(String locale) {
        if (!locales.contains(locale)) throw new IllegalArgumentException("Locale is not configured for YANDU sync: " + locale);
        refreshLocale(locale);
    }

    private void refreshLocale(String locale) {
        var metadata = client.get()
                .uri("/api/v1/public/projects/{projectId}/translations/{locale}/latest?format={format}",
                        properties.getProjectId(), locale, properties.getFormat())
                .retrieve().body(ReleaseMetadata.class);
        if (metadata == null || metadata.checksumSha256() == null) {
            throw new IllegalStateException("YANDU returned incomplete release metadata");
        }
        if (metadata.checksumSha256().equals(catalog.version(locale))) return;
        var content = client.get()
                .uri("/api/v1/public/projects/{projectId}/translations/{locale}/latest/content?format={format}",
                        properties.getProjectId(), locale, properties.getFormat())
                .retrieve().body(String.class);
        if (content == null || !sha256(content).equals(metadata.checksumSha256())) {
            throw new IllegalStateException("YANDU release content did not match its SHA-256 checksum");
        }
        catalog.replace(locale, metadata.checksumSha256(), parse(content));
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
            if ("polling".equalsIgnoreCase(properties.getMode())) {
                scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                    var thread = new Thread(task, "yandu-translation-sync");
                    thread.setDaemon(true);
                    return thread;
                });
                scheduler.scheduleWithFixedDelay(this::refreshAllQuietly, 0, properties.getRefreshIntervalMs(),
                        TimeUnit.MILLISECONDS);
            } else {
                refreshAllQuietly();
                var transport = transports.stream()
                        .filter(candidate -> properties.getMode().equalsIgnoreCase(candidate.mode()))
                        .findFirst().orElseThrow(() -> new IllegalStateException(
                                "Add the yandu-translation-sync-" + properties.getMode()
                                        + " module to enable this transport"));
                var connections = new java.util.ArrayList<AutoCloseable>();
                for (var locale : locales) {
                    var localeProperties = propertiesFor(locale);
                    connections.add(transport.connect(localeProperties, json, () -> catalog.version(locale),
                            () -> refreshQuietly(locale)));
                }
                realtimeConnections = List.copyOf(connections);
            }
        }
    }

    private void refreshAllQuietly() {
        for (var locale : locales) refreshQuietly(locale);
    }

    private void refreshQuietly(String locale) {
        try {
            refreshNow(locale);
        } catch (RuntimeException exception) {
            // Keep the last verified snapshot; next poll retries automatically.
            log.warn("YANDU translation sync failed for locale " + locale + "; retaining the last verified snapshot ("
                    + exception.getClass().getSimpleName() + ")");
        }
    }

    private TranslationSyncProperties propertiesFor(String locale) {
        var copy = new TranslationSyncProperties();
        copy.setEnabled(properties.isEnabled());
        copy.setMode(properties.getMode());
        copy.setApiBaseUrl(properties.getApiBaseUrl());
        copy.setProjectId(properties.getProjectId());
        copy.setLocales(List.of(locale));
        copy.setFormat(properties.getFormat());
        copy.setRefreshIntervalMs(properties.getRefreshIntervalMs());
        copy.setNettyHost(properties.getNettyHost());
        copy.setNettyPort(properties.getNettyPort());
        copy.setWebsocketUrl(properties.getWebsocketUrl());
        return copy;
    }

    @Override
    public synchronized void stop() {
        for (var connection : realtimeConnections) {
            try { connection.close(); } catch (Exception ignored) { }
        }
        realtimeConnections = List.of();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return (scheduler != null && !scheduler.isShutdown()) || !realtimeConnections.isEmpty();
    }

    @Override
    public boolean isAutoStartup() { return true; }

    @Override
    public int getPhase() { return Integer.MAX_VALUE; }

    public record ReleaseMetadata(String id, String projectId, String locale, String format,
                                  long version, String checksumSha256, String publishedAt) { }
}
