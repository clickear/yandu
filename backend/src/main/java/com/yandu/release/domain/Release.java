package com.yandu.release.domain;

import com.yandu.quality.PlaceholderRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable, quality-checked release content prepared from the current project translations. */
public final class Release {

    public static final String JSON_FLAT = "json-flat-v1";
    public static final String JAVA_PROPERTIES = "java-properties-v1";

    private final String locale;
    private final String format;
    private final Map<String, String> content;
    private final List<ManifestEntry> entries;

    private Release(String locale, String format, Map<String, String> content, List<ManifestEntry> entries) {
        this.locale = locale;
        this.format = format;
        this.content = Collections.unmodifiableMap(new TreeMap<>(content));
        this.entries = List.copyOf(entries);
    }

    /** Applies release eligibility rules before an application service serializes or persists a snapshot. */
    public static Release prepare(String locale, String format, List<Entry> source) {
        if (locale == null || locale.isBlank()) throw new IllegalArgumentException("Release locale is required");
        format = normalizeFormat(format);
        if (source.isEmpty()) throw new RejectedException(List.of("项目没有可发布的资源条目"));

        var content = new TreeMap<String, String>();
        var manifestEntries = new ArrayList<ManifestEntry>();
        var failures = new ArrayList<String>();
        for (var entry : source) {
            validate(entry, locale, failures);
            if (content.containsKey(entry.key())) {
                failures.add("资源键在多个命名空间中重复: " + entry.key());
            } else {
                content.put(entry.key(), entry.translation());
            }
            manifestEntries.add(new ManifestEntry(Long.toString(entry.entryId()), entry.key(),
                    entry.sourceRevision(), entry.translationVersion()));
        }
        if (!failures.isEmpty()) throw new RejectedException(failures);
        return new Release(locale, format, content, manifestEntries);
    }

    public static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return JSON_FLAT;
        if (!JSON_FLAT.equals(format) && !JAVA_PROPERTIES.equals(format)) {
            throw new IllegalArgumentException("format must be json-flat-v1 or java-properties-v1");
        }
        return format;
    }

    private static void validate(Entry entry, String locale, List<String> failures) {
        var label = entry.key() + "（" + locale + "）：";
        if (entry.translation() == null || entry.translation().isBlank()) {
            failures.add(label + "译文不能为空");
            return;
        }
        if (!"APPROVED".equals(entry.status())) {
            failures.add(label + "译文尚未审核通过");
            return;
        }
        if (entry.translationSourceRevision() != entry.sourceRevision()) {
            failures.add(label + "译文基于旧版源文");
            return;
        }
        var placeholders = PlaceholderRules.compare(entry.sourceText(), entry.translation());
        if (!placeholders.sourceSupported()) {
            failures.add(label + "源文包含不支持的花括号格式；仅支持 {name}，双花括号可表示字面花括号");
        }
        if (!placeholders.translationSupported()) {
            failures.add(label + "译文包含不支持的花括号格式；仅支持 {name}，双花括号可表示字面花括号");
        }
        if (placeholders.sourceSupported() && placeholders.translationSupported()
                && !placeholders.source().equals(placeholders.translation())) {
            failures.add(label + "命名占位符与源文不一致");
        }
    }

    public String locale() { return locale; }
    public String format() { return format; }
    public Map<String, String> content() { return content; }
    public List<ManifestEntry> entries() { return entries; }
    public int entryCount() { return content.size(); }

    public Manifest manifest() {
        return new Manifest(format, locale, entries, List.of("PLACEHOLDER_MISMATCH"));
    }

    /** One current translation row and the versions against which it is eligible for release. */
    public record Entry(long entryId, String key, String sourceText, long sourceRevision,
                        String translation, String status, long translationSourceRevision,
                        long translationVersion) { }

    public record Manifest(String format, String locale, List<ManifestEntry> entries,
                           List<String> passedQualityRules) {
        public Manifest {
            entries = List.copyOf(entries);
            passedQualityRules = List.copyOf(passedQualityRules);
        }
    }

    public record ManifestEntry(String entryId, String key, long sourceRevision,
                                long translationVersion) { }

    public static final class RejectedException extends RuntimeException {
        private final List<String> failures;

        public RejectedException(List<String> failures) {
            super("Release quality gate failed");
            this.failures = List.copyOf(failures);
        }

        public List<String> failures() { return failures; }
    }
}
