package com.yandu.imports.infrastructure.mysql;

import com.yandu.imports.application.ImportRepository;
import com.yandu.imports.application.ImportRepository.Job;
import com.yandu.imports.application.ImportRepository.ProjectMigration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisImportRepository implements ImportRepository, ImportRepository.ProjectMigrations {
    private final ImportMapper mapper;

    public MyBatisImportRepository(ImportMapper mapper) { this.mapper = mapper; }

    @Override public Optional<Job> find(long projectId, long jobId) {
        return Optional.ofNullable(mapper.find(projectId, jobId)).map(this::job);
    }
    @Override public Optional<Job> findForUpdate(long projectId, long jobId) {
        return Optional.ofNullable(mapper.findForUpdate(projectId, jobId)).map(this::job);
    }
    @Override public Optional<Job> findByIdempotencyKey(long projectId, String key) {
        return Optional.ofNullable(mapper.findByKey(projectId, key)).map(this::job);
    }
    @Override public List<JobSummary> findRecent(long projectId, int limit) {
        return mapper.findRecent(projectId, limit).stream().map(row -> new JobSummary(
                row.getId(), row.getLocale(), row.getStatus(), row.getPreviewHash(), row.getAdded(),
                row.getUpdated(), row.getUnchanged(), row.getConflicts(), row.getError(),
                row.getCreatedAt(), row.getUpdatedAt())).toList();
    }
    @Override public List<Job> findQueued(int limit) {
        return mapper.findQueued(limit).stream().map(this::job).toList();
    }
    @Override public int recoverStale(Instant runningBefore, Instant updatedAt) {
        return mapper.recoverStale(runningBefore, updatedAt);
    }
    @Override public void insert(Job job) {
        var row = new ImportMapper.ImportJobRow();
        row.setId(job.id()); row.setProjectId(job.projectId()); row.setNamespaceId(job.namespaceId());
        row.setLocale(job.locale()); row.setProjectVersion(job.projectVersion());
        row.setIdempotencyKey(job.idempotencyKey()); row.setPayloadJson(job.payloadJson());
        row.setPreviewJson(job.previewJson()); row.setPreviewHash(job.previewHash());
        row.setStatus(job.status()); row.setCreatedBy(job.createdBy());
        row.setCreatedAt(job.createdAt()); row.setUpdatedAt(job.updatedAt()); row.setError(job.error());
        row.setRunGeneration(job.runGeneration());
        mapper.insertJob(row);
    }
    @Override public boolean queue(long projectId, long jobId, String hash, Instant at) {
        return mapper.queue(projectId, jobId, hash, at) == 1;
    }
    @Override
    @Transactional
    public Optional<Job> claim(long jobId, Instant at) {
        if (mapper.claim(jobId, at) != 1) return Optional.empty();
        return Optional.ofNullable(mapper.findByJobId(jobId)).map(this::job);
    }
    @Override public boolean complete(long jobId, long runGeneration, Instant at) {
        return mapper.complete(jobId, runGeneration, at) == 1;
    }
    @Override public boolean fail(long jobId, long runGeneration, String error, Instant at) {
        return mapper.fail(jobId, runGeneration, error, at) == 1;
    }

    @Override public Optional<ProjectMigration> find(long id) {
        return Optional.ofNullable(mapper.findMigration(id)).map(this::migration);
    }

    @Override public Optional<ProjectMigration> findForUpdate(long id) {
        return Optional.ofNullable(mapper.findMigrationForUpdate(id)).map(this::migration);
    }

    @Override public Optional<ProjectMigration> findBySourceProject(long actorId, String sourceProjectId) {
        return Optional.ofNullable(mapper.findMigrationBySource(actorId, sourceProjectId)).map(this::migration);
    }

    @Override public List<ProjectMigration> findQueuedMigrations(int limit) {
        return mapper.findQueuedMigrations(limit).stream().map(this::migration).toList();
    }

    @Override public boolean claimMigration(long id, Instant updatedAt) {
        return mapper.claimMigration(id, updatedAt) == 1;
    }

    @Override public void insert(ProjectMigration migration) {
        mapper.insertMigration(migrationRow(migration));
    }

    @Override public boolean save(ProjectMigration migration, long expectedVersion) {
        return mapper.saveMigration(migrationRow(migration), expectedVersion) == 1;
    }

    @Override public int recoverStaleMigrations(Instant runningBefore, Instant updatedAt) {
        return mapper.recoverStaleMigrations(runningBefore, updatedAt);
    }

    private Job job(ImportMapper.ImportJobRow row) {
        return new Job(row.getId(), row.getProjectId(), row.getNamespaceId(), row.getLocale(),
                row.getProjectVersion(), row.getIdempotencyKey(), row.getPayloadJson(),
                row.getPreviewJson(), row.getPreviewHash(), row.getStatus(), row.getCreatedBy(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getError(), row.getRunGeneration());
    }

    private ProjectMigration migration(ImportMapper.ProjectMigrationRow row) {
        return new ProjectMigration(row.getId(), row.getActorId(), row.getSourceProjectId(),
                row.getPackageJson(), row.getPackageHash(), row.getTargetName(), row.getPreviewJson(),
                row.getPreviewHash(), row.getStatus(), row.getTargetProjectId(),
                row.getCompletedEntries(), row.getEntryIdMapJson(), row.getVersion(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getError());
    }

    private ImportMapper.ProjectMigrationRow migrationRow(ProjectMigration migration) {
        var row = new ImportMapper.ProjectMigrationRow();
        row.setId(migration.id()); row.setActorId(migration.actorId());
        row.setSourceProjectId(migration.sourceProjectId()); row.setPackageJson(migration.packageJson());
        row.setPackageHash(migration.packageHash()); row.setTargetName(migration.targetName());
        row.setPreviewJson(migration.previewJson()); row.setPreviewHash(migration.previewHash());
        row.setStatus(migration.status()); row.setTargetProjectId(migration.targetProjectId());
        row.setCompletedEntries(migration.completedEntries()); row.setEntryIdMapJson(migration.entryIdMapJson());
        row.setVersion(migration.version()); row.setCreatedAt(migration.createdAt());
        row.setUpdatedAt(migration.updatedAt()); row.setError(migration.error());
        return row;
    }
}
