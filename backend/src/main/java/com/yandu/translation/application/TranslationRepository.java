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

    void appendRevision(Translation translation, long actorId, Instant occurredAt);

    void recordDecision(Translation translation, long decidedVersion, Translation.Decision decision,
                       long reviewerId, String reason, Instant occurredAt);

    void markNeedsReview(long projectId, long entryId, long sourceRevision,
                         long sourceEditorId, Instant occurredAt);

    record WorkspaceEntry(long entryId, String key, String sourceText, long sourceRevision,
                          Long translationId, String translation, String status,
                          Long translationVersion, Long translationSourceRevision) { }

    record WorkspaceSearch(List<WorkspaceEntry> items, long filteredTotal, long totalEntries,
                           long translated, long reviewed) { }

    record MemoryMatch(long entryId, String key, String sourceText, String translation,
                       String locale, long translationVersion) { }
}
