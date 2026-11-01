package com.yandu.imports.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for previewed, idempotent JSON import jobs. */
public interface ImportRepository {

    Optional<Job> find(long projectId, long jobId);

    Optional<Job> findByIdempotencyKey(long projectId, String idempotencyKey);

    List<JobSummary> findRecent(long projectId, int limit);

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

    record JobSummary(long id, String locale, String status, String previewHash,
                      int added, int updated, int unchanged, int conflicts, String error,
                      Instant createdAt, Instant updatedAt) { }

    /** Persistence port for the independently-lived local-project migration workflow. */
    interface ProjectMigrations {
        Optional<ProjectMigration> find(long migrationId);

        Optional<ProjectMigration> findForUpdate(long migrationId);

        Optional<ProjectMigration> findBySourceProject(long actorId, String sourceProjectId);

        List<ProjectMigration> findQueuedMigrations(int limit);

        boolean claimMigration(long migrationId, Instant updatedAt);

        void insert(ProjectMigration migration);

        boolean save(ProjectMigration migration, long expectedVersion);

        int recoverStaleMigrations(Instant runningBefore, Instant updatedAt);
    }

    record ProjectMigration(long id, long actorId, String sourceProjectId, String packageJson,
                            String packageHash, String targetName, String previewJson,
                            String previewHash, String status, Long targetProjectId,
                            int completedEntries, String entryIdMapJson, long version,
                            Instant createdAt, Instant updatedAt, String error) { }
}
