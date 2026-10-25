package com.yandu.translation.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One target-locale translation for a catalog entry. */
public final class Translation {

    private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*");

    private final long id;
    private final long projectId;
    private final long entryId;
    private final String locale;
    private String value = "";
    private long basedOnSourceRevision;
    private Status status = Status.DRAFT;
    private long version;
    private long translatedBy;
    private long updatedBy;

    private Translation(long id, long projectId, long entryId, String locale,
                        String value, long basedOnSourceRevision, Status status,
                        long version, long translatedBy, long updatedBy) {
        if (id <= 0 || projectId <= 0 || entryId <= 0 || basedOnSourceRevision < 0
                || version < 0 || translatedBy < 0 || updatedBy < 0) {
            throw new IllegalArgumentException("Translation identifiers and revisions are invalid");
        }
        this.id = id;
        this.projectId = projectId;
        this.entryId = entryId;
        this.locale = normalizeLocale(locale);
        this.value = Objects.requireNonNull(value, "value");
        this.basedOnSourceRevision = basedOnSourceRevision;
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
        this.translatedBy = translatedBy;
        this.updatedBy = updatedBy;
    }

    public static Translation newDraft(long id, long projectId, long entryId, String locale) {
        return new Translation(id, projectId, entryId, locale, "", 0,
                Status.DRAFT, 0, 0, 0);
    }

    public static Translation restore(long id, long projectId, long entryId, String locale,
                                      String value, long basedOnSourceRevision, Status status,
                                      long version, long translatedBy, long updatedBy) {
        return new Translation(id, projectId, entryId, locale, value,
                basedOnSourceRevision, status, version, translatedBy, updatedBy);
    }

    public boolean save(long expectedVersion, long basedOnRevision, long currentSourceRevision,
                        String newValue, long actorId) {
        checkVersion(expectedVersion);
        if (actorId <= 0 || basedOnRevision < 1 || currentSourceRevision < 1
                || basedOnRevision > currentSourceRevision) {
            throw new IllegalArgumentException("Actor and source revisions are invalid");
        }
        var validValue = requireValue(newValue);
        if (value.equals(validValue) && basedOnSourceRevision == basedOnRevision) {
            return false;
        }
        value = validValue;
        basedOnSourceRevision = basedOnRevision;
        translatedBy = actorId;
        updatedBy = actorId;
        status = basedOnRevision < currentSourceRevision ? Status.NEEDS_REVIEW : Status.DRAFT;
        version = Math.incrementExact(version);
        return true;
    }

    public boolean submitForReview(long expectedVersion, long currentSourceRevision, long actorId) {
        checkVersion(expectedVersion);
        if (actorId <= 0 || currentSourceRevision < 1) {
            throw new IllegalArgumentException("Actor and source revision are required");
        }
        if (status != Status.DRAFT && status != Status.CHANGES_REQUESTED) {
            throw new IllegalStateException("Only a draft translation can be submitted for review");
        }
        if (value.isBlank() || basedOnSourceRevision != currentSourceRevision) {
            throw new IllegalStateException("Translation must contain text based on the current source revision");
        }
        status = Status.IN_REVIEW;
        updatedBy = actorId;
        version = Math.incrementExact(version);
        return true;
    }

    public boolean decide(long expectedVersion, long currentSourceRevision, long reviewerId,
                          Decision decision, String reason) {
        checkVersion(expectedVersion);
        if (reviewerId <= 0 || decision == null || currentSourceRevision < 1) {
            throw new IllegalArgumentException("Reviewer, decision and source revision are required");
        }
        if (status != Status.IN_REVIEW || basedOnSourceRevision != currentSourceRevision) {
            throw new IllegalStateException("Only a current translation under review can be decided");
        }
        if (reviewerId == translatedBy) {
            throw new IllegalStateException("A translator cannot review their own translation");
        }
        if (decision == Decision.REQUEST_CHANGES && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A reason is required when requesting changes");
        }
        status = decision == Decision.APPROVE ? Status.APPROVED : Status.CHANGES_REQUESTED;
        updatedBy = reviewerId;
        version = Math.incrementExact(version);
        return true;
    }

    public boolean markNeedsReview(long currentSourceRevision, long sourceEditorId) {
        if (currentSourceRevision < 1 || sourceEditorId <= 0) {
            throw new IllegalArgumentException("Source revision and editor are required");
        }
        if (version == 0 || basedOnSourceRevision >= currentSourceRevision
                || status == Status.NEEDS_REVIEW) {
            return false;
        }
        status = Status.NEEDS_REVIEW;
        updatedBy = sourceEditorId;
        version = Math.incrementExact(version);
        return true;
    }

    private void checkVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new VersionConflictException(expectedVersion, version);
        }
    }

    private static String requireValue(String value) {
        Objects.requireNonNull(value, "value");
        if (value.length() > 16_000) throw new IllegalArgumentException("Translation value is too long");
        return value;
    }

    public static String normalizeLocale(String locale) {
        if (locale == null || !LANGUAGE_TAG.matcher(locale).matches()) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        var normalized = Locale.forLanguageTag(locale).toLanguageTag();
        if (normalized.equals("und")) throw new IllegalArgumentException("locale is invalid");
        return normalized;
    }

    public long id() { return id; }
    public long projectId() { return projectId; }
    public long entryId() { return entryId; }
    public String locale() { return locale; }
    public String value() { return value; }
    public long basedOnSourceRevision() { return basedOnSourceRevision; }
    public Status status() { return status; }
    public long version() { return version; }
    public long translatedBy() { return translatedBy; }
    public long updatedBy() { return updatedBy; }

    public enum Status { DRAFT, IN_REVIEW, APPROVED, CHANGES_REQUESTED, NEEDS_REVIEW }

    public enum Decision { APPROVE, REQUEST_CHANGES }

    public static final class VersionConflictException extends RuntimeException {
        private final long expectedVersion;
        private final long actualVersion;

        public VersionConflictException(long expectedVersion, long actualVersion) {
            super("Expected translation version " + expectedVersion + " but found " + actualVersion);
            this.expectedVersion = expectedVersion;
            this.actualVersion = actualVersion;
        }

        public long expectedVersion() { return expectedVersion; }
        public long actualVersion() { return actualVersion; }
    }
}
