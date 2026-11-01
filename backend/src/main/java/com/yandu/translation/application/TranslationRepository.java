package com.yandu.translation.application;

import com.yandu.translation.domain.Translation;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** Persistence port for one entry/target-locale translation and immutable history. */
public interface TranslationRepository {

    Optional<Translation> find(long projectId, long entryId, String locale);

    WorkspaceSearch searchWorkspace(long projectId, long namespaceId, String locale,
                                    String status, String keyword, int offset, int limit);

    List<MemoryMatch> findApprovedMatches(long projectId, long excludedEntryId, String locale,
                                          String sourceText, int limit);

    void insert(Translation translation, Instant occurredAt);

    void update(Translation translation, long expectedVersion, Instant occurredAt);

    final class VersionConflictException extends RuntimeException {
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

    void appendRevision(Translation translation, long actorId, Instant occurredAt);

    void recordDecision(Translation translation, long decidedVersion, Translation.Decision decision,
                       long reviewerId, String reason, Instant occurredAt);

    Optional<ReviewFeedback> latestReviewFeedback(long projectId, long entryId, String locale);

    void markNeedsReview(long projectId, long entryId, long sourceRevision,
                         long sourceEditorId, Instant occurredAt);

    record WorkspaceEntry(long entryId, String key, String sourceText, long sourceRevision,
                          Long translationId, String translation, String status,
                          Long translationVersion, Long translationSourceRevision) { }

    record WorkspaceSearch(List<WorkspaceEntry> items, long filteredTotal, long totalEntries,
                           long translated, long reviewed) { }

    record MemoryMatch(long entryId, String key, String sourceText, String translation,
                       String locale, long translationVersion) { }

    record ReviewFeedback(String decision, String reason, long reviewerId, Instant decidedAt) { }
}
