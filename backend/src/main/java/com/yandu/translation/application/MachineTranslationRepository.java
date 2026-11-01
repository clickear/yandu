package com.yandu.translation.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for durable machine-translation jobs and their per-entry work items. */
public interface MachineTranslationRepository {

    JobCreation create(JobDraft draft);

    Optional<Job> find(long projectId, long jobId);

    Optional<Job> findByIdempotencyKey(long projectId, long actorId, String idempotencyKey);

    List<Job> list(long projectId, int limit);

    long countActiveEntries(long projectId, List<Long> entryIds);

    List<JobItem> items(long projectId, long jobId, int offset, int limit);

    long itemCount(long projectId, long jobId);

    List<Long> unfinishedJobIds();

    int retryFailed(long projectId, long jobId, Instant updatedAt);

    void recoverStale(Instant staleBefore, Instant updatedAt);

    Optional<JobItem> claimNext(Instant updatedAt);

    void releaseClaim(JobItem item, Instant updatedAt);

    void completeItem(long jobId, long entryId, String status, String message, Instant updatedAt);

    record JobDraft(long id, long projectId, String sourceLocale, String locale, long actorId, String idempotencyKey,
                    String requestHash, List<Long> entryIds, Instant createdAt) { }

    record JobCreation(Job job, boolean created) { }

    record Job(long id, long projectId, String locale, String status, long totalCount,
               long completedCount, long skippedCount, long failedCount, long createdBy, String requestHash,
               Instant createdAt, Instant updatedAt, String error) { }

    record JobItem(long jobId, long projectId, String sourceLocale, String locale, long entryId,
                   Long namespaceId, String entryKey, String sourceText, long sourceRevision,
                   Long expectedTranslationVersion, long actorId, int attempts,
                   String status, String error) { }
}
