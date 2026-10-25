package com.yandu.catalog.domain;

import java.util.Objects;

/** A source resource in a project. It owns source text and its revision history boundary. */
public final class CatalogEntry {

    private final long id;
    private final long projectId;
    private final String key;
    private String sourceText;
    private long sourceRevision;
    private long version;

    public CatalogEntry(long id, long projectId, String key, String sourceText,
                        long sourceRevision, long version) {
        if (id <= 0 || projectId <= 0) {
            throw new IllegalArgumentException("id and projectId must be positive");
        }
        this.id = id;
        this.projectId = projectId;
        this.key = normalizeKey(key);
        this.sourceText = requireText(sourceText, "sourceText");
        if (sourceRevision < 1 || version < 1) {
            throw new IllegalArgumentException("sourceRevision and version must be positive");
        }
        this.sourceRevision = sourceRevision;
        this.version = version;
    }

    public SourceChange changeSource(long expectedRevision, String newSourceText) {
        if (expectedRevision != sourceRevision) {
            throw new SourceRevisionConflictException(expectedRevision, sourceRevision);
        }

        var validSource = requireText(newSourceText, "newSourceText");
        if (sourceText.equals(validSource)) {
            return new SourceChange(sourceRevision, sourceRevision, version, version, false);
        }

        var previousRevision = sourceRevision;
        var previousVersion = version;
        var currentRevision = Math.incrementExact(sourceRevision);
        var currentVersion = Math.incrementExact(version);
        sourceRevision = currentRevision;
        version = currentVersion;
        sourceText = validSource;
        return new SourceChange(previousRevision, currentRevision, previousVersion, currentVersion,
                true);
    }

    public long id() {
        return id;
    }

    public long projectId() {
        return projectId;
    }

    public String key() {
        return key;
    }

    public String sourceText() {
        return sourceText;
    }

    public long sourceRevision() {
        return sourceRevision;
    }

    public long version() {
        return version;
    }

    private static String normalizeKey(String value) {
        var key = requireText(value, "key").strip();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        if (key.length() > 200) {
            throw new IllegalArgumentException("key must contain at most 200 characters");
        }
        return key;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record SourceChange(
            long previousRevision,
            long currentRevision,
            long previousVersion,
            long currentVersion,
            boolean changed) {

    }

    public static final class SourceRevisionConflictException extends RuntimeException {

        private final long expectedRevision;
        private final long actualRevision;

        public SourceRevisionConflictException(long expectedRevision, long actualRevision) {
            super("Expected source revision " + expectedRevision + " but found " + actualRevision);
            this.expectedRevision = expectedRevision;
            this.actualRevision = actualRevision;
        }

        public long expectedRevision() {
            return expectedRevision;
        }

        public long actualRevision() {
            return actualRevision;
        }
    }
}
