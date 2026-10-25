package com.yandu.imports.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for previewed, idempotent JSON import jobs. */
public interface ImportRepository {

    Optional<Job> find(long projectId, long jobId);

    Optional<Job> findByIdempotencyKey(long projectId, String idempotencyKey);

    List<Job> findQueued(int limit);

    int recoverStale(Instant runningBefore, Instant updatedAt);

    void insert(Job job);

    boolean queue(long projectId, long jobId, String previewHash, Instant updatedAt);

    boolean claim(long jobId, Instant updatedAt);

    void complete(long jobId, Instant updatedAt);

    boolean fail(long jobId, String error, Instant updatedAt);

    record Job(long id, long projectId, long namespaceId, String locale, long projectVersion,
               String idempotencyKey, String payloadJson, String previewJson, String previewHash,
               String status, long createdBy, Instant createdAt, Instant updatedAt, String error) { }
}
