package com.yandu.catalog.domain;

import java.util.Objects;

/** A source resource in a project. It owns source text and its revision history boundary. */
public final class CatalogEntry {

    private final long id;
    private final long projectId;
    private long namespaceId;
    private String key;
    private String sourceText;
    private long sourceRevision;
    private long version;
    private Status status;

    public CatalogEntry(long id, long projectId, long namespaceId, String key, String sourceText,
                        long sourceRevision, long version) {
        this(id, projectId, namespaceId, key, sourceText, sourceRevision, version, Status.ACTIVE);
    }

    public CatalogEntry(long id, long projectId, long namespaceId, String key, String sourceText,
                        long sourceRevision, long version, Status status) {
        if (id <= 0 || projectId <= 0 || namespaceId <= 0) {
            throw new IllegalArgumentException("id, projectId and namespaceId must be positive");
        }
        this.id = id;
        this.projectId = projectId;
        this.namespaceId = namespaceId;
        this.key = normalizeKey(key);
        this.sourceText = requireText(sourceText, "sourceText");
        if (sourceRevision < 1 || version < 1) {
            throw new IllegalArgumentException("sourceRevision and version must be positive");
        }
        this.sourceRevision = sourceRevision;
        this.version = version;
        this.status = Objects.requireNonNull(status, "status");
    }

    public LifecycleChange archive() {
        return changeStatus(Status.ACTIVE, Status.ARCHIVED);
    }

    public LifecycleChange restore() {
        return changeStatus(Status.ARCHIVED, Status.ACTIVE);
    }

    private LifecycleChange changeStatus(Status expected, Status next) {
        if (status != expected) {
            throw new IllegalStateException("Cannot change catalog entry from " + status + " to " + next);
        }
        var previousVersion = version;
        status = next;
        version = Math.incrementExact(version);
        return new LifecycleChange(expected, next, previousVersion, version);
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("Archived catalog entries cannot be edited");
        }
    }

    public SourceChange changeSource(long expectedRevision, String newSourceText) {
        requireActive();
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

    public long namespaceId() { return namespaceId; }

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

    public Status status() { return status; }

    /** Reorganizes metadata without changing source revision or translation identity. */
    public Reorganization reorganize(long expectedVersion, long targetNamespaceId, String newKey) {
        requireActive();
        if (expectedVersion != version) {
            throw new VersionConflictException(expectedVersion, version);
        }
        if (targetNamespaceId <= 0) throw new IllegalArgumentException("namespaceId must be positive");
        var normalizedKey = normalizeKey(newKey);
        var previousNamespaceId = namespaceId;
        var previousKey = key;
        if (previousNamespaceId == targetNamespaceId && previousKey.equals(normalizedKey)) {
            return new Reorganization(previousNamespaceId, previousKey, version, version, false);
        }
        var previousVersion = version;
        namespaceId = targetNamespaceId;
        key = normalizedKey;
        version = Math.incrementExact(version);
        return new Reorganization(previousNamespaceId, previousKey, previousVersion, version, true);
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

    public record Reorganization(long previousNamespaceId, String previousKey,
                                 long previousVersion, long currentVersion, boolean changed) { }

    public enum Status { ACTIVE, ARCHIVED }

    public record LifecycleChange(Status previousStatus, Status currentStatus,
                                  long previousVersion, long currentVersion) { }

    public static final class VersionConflictException extends RuntimeException {
        private final long expectedVersion;
        private final long actualVersion;
        public VersionConflictException(long expectedVersion, long actualVersion) {
            super("Expected catalog version " + expectedVersion + " but found " + actualVersion);
            this.expectedVersion = expectedVersion;
            this.actualVersion = actualVersion;
        }
        public long expectedVersion() { return expectedVersion; }
        public long actualVersion() { return actualVersion; }
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
