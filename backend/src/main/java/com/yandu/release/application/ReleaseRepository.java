package com.yandu.release.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for immutable project-locale release artifacts. */
public interface ReleaseRepository {

    ReleaseSnapshot create(long id, long projectId, String locale, String format,
                           String content, String manifest,
                           Long rollbackOfReleaseId,
                           String checksum, long actorId, Instant createdAt);

    Optional<ReleaseSnapshot> find(long projectId, long releaseId);

    Optional<ReleaseMetadata> latestPublished(long projectId, String locale, String format);

    List<ReleaseSnapshot> list(long projectId, String locale, String format, int limit);

    List<ReleaseLine> lines(long projectId, String locale);

    Optional<ReleaseJob> findJob(long projectId, long jobId);

    Optional<ReleaseJob> findJobForUpdate(long projectId, long jobId);

    Optional<ReleaseJob> findJobByKey(long projectId, long actorId, String idempotencyKey);

    List<ReleaseJob> findQueuedJobs(int limit);

    List<ReleaseJob> listJobs(long projectId, String locale, int limit);

    void insertJob(ReleaseJob job);

    boolean claimJob(long jobId, Instant updatedAt);

    boolean completeJob(long jobId, long releaseId, Instant updatedAt);

    boolean failJob(long jobId, String error, Instant updatedAt);

    boolean retryJob(long projectId, long jobId, Instant updatedAt);

    int recoverStaleJobs(Instant runningBefore, Instant updatedAt);

    record ReleaseSnapshot(long id, long projectId, String locale, String format, long version,
                           Long rollbackOfReleaseId,
                           String content, String manifest, String checksum,
                           long publishedBy, Instant publishedAt) { }

    record ReleaseMetadata(long id, long projectId, String locale, String format, long version,
                           Long rollbackOfReleaseId, String manifest, String checksum,
                           long publishedBy, Instant publishedAt) { }

    record ReleaseLine(long entryId, String key, String sourceText, long sourceRevision,
                       String translation, String status, long translationSourceRevision,
                       long translationVersion) { }

    record ReleaseJob(long id, long projectId, String locale, String format,
                      String idempotencyKey, String requestHash, String status,
                      Long releaseId, String error, long createdBy,
                      Instant createdAt, Instant updatedAt) { }
}
