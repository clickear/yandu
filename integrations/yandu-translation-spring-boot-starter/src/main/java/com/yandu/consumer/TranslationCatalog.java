package com.yandu.consumer;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only view of the latest completely downloaded translation snapshot. */
public class TranslationCatalog {

    private final AtomicReference<Snapshot> current = new AtomicReference<>(new Snapshot(null, Map.of()));

    public Optional<String> find(String key) {
        return Optional.ofNullable(current.get().values().get(key));
    }

    public String require(String key) {
        return find(key).orElseThrow(() -> new MissingTranslationException(key));
    }

    public String version() {
        return current.get().version();
    }

    public Map<String, String> snapshot() {
        return current.get().values();
    }

    void replace(String version, Map<String, String> values) {
        current.set(new Snapshot(version, Map.copyOf(values)));
    }

    private record Snapshot(String version, Map<String, String> values) { }

    public static class MissingTranslationException extends RuntimeException {
        public MissingTranslationException(String key) {
            super("No published translation exists for key: " + key);
        }
    }
}
