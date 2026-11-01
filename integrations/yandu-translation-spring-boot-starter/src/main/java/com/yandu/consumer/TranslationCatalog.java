package com.yandu.consumer;

import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only view of the latest completely downloaded translation snapshot. */
public class TranslationCatalog {

    private final AtomicReference<Map<String, Snapshot>> current = new AtomicReference<>(Map.of());
    private volatile String defaultLocale = "";
    private volatile List<String> supportedLocales = List.of();

    public Optional<String> find(String key) {
        return find(defaultLocale, key);
    }

    public Optional<String> find(String locale, String key) {
        var snapshot = snapshotFor(locale);
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.values().get(key));
    }

    public String require(String key) {
        return find(key).orElseThrow(() -> new MissingTranslationException(key));
    }

    public String version() {
        var snapshot = snapshotFor(defaultLocale);
        return snapshot == null ? null : snapshot.version();
    }

    public String version(String locale) {
        var snapshot = snapshotFor(locale);
        return snapshot == null ? null : snapshot.version();
    }

    public Map<String, String> snapshot() {
        var snapshot = snapshotFor(defaultLocale);
        return snapshot == null ? Map.of() : snapshot.values();
    }

    public Map<String, String> snapshot(String locale) {
        var snapshot = snapshotFor(locale);
        return snapshot == null ? Map.of() : snapshot.values();
    }

    void replace(String version, Map<String, String> values) {
        replace(defaultLocale, version, values);
    }

    void replace(String locale, String version, Map<String, String> values) {
        var next = new java.util.HashMap<>(current.get());
        next.put(normalize(locale), new Snapshot(version, Map.copyOf(values)));
        current.set(Map.copyOf(next));
    }

    void setDefaultLocale(String locale) { defaultLocale = normalize(locale); }

    public List<String> supportedLocales() { return supportedLocales; }

    void setSupportedLocales(List<String> locales) {
        supportedLocales = locales.stream().map(TranslationCatalog::normalize).distinct().toList();
        var supported = java.util.Set.copyOf(supportedLocales);
        current.updateAndGet(snapshots -> snapshots.entrySet().stream()
                .filter(entry -> supported.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)));
        if (!supportedLocales.isEmpty()) defaultLocale = supportedLocales.get(0);
    }

    private Snapshot snapshotFor(String locale) {
        var snapshots = current.get();
        var requested = normalize(locale);
        var snapshot = snapshots.get(requested);
        if (snapshot != null || requested.isEmpty()) return snapshot;
        return snapshots.get("");
    }

    private static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return "";
        return Locale.forLanguageTag(locale).toLanguageTag().toLowerCase(Locale.ROOT);
    }

    private record Snapshot(String version, Map<String, String> values) { }

    public static class MissingTranslationException extends RuntimeException {
        public MissingTranslationException(String key) {
            super("No published translation exists for key: " + key);
        }
    }
}
