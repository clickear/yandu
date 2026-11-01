package com.yandu.release.infrastructure.mysql;

import com.yandu.release.application.ReleaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisReleaseRepository implements ReleaseRepository {

    private final ReleaseMapper mapper;

    public MyBatisReleaseRepository(ReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReleaseSnapshot create(long id, long projectId, String locale, String format,
                                  String content, String manifest,
                                  Long rollbackOfReleaseId, String checksum, long actorId,
                                  Instant createdAt) {
        if (mapper.lockActiveProject(projectId) == null) {
            throw new IllegalStateException("Project is not active");
        }
        long version = mapper.nextVersion(projectId, locale, format);
        mapper.insertSnapshot(id, projectId, locale, format, version, rollbackOfReleaseId,
                content, manifest, checksum, actorId, createdAt);
        return new ReleaseSnapshot(id, projectId, locale, format, version, rollbackOfReleaseId, content, manifest,
                checksum, actorId, createdAt);
    }

    @Override
    public Optional<ReleaseSnapshot> find(long projectId, long releaseId) {
        return Optional.ofNullable(mapper.findSnapshot(projectId, releaseId)).map(this::snapshot);
    }

    @Override
    public List<ReleaseSnapshot> list(long projectId, String locale, String format, int limit) {
        return mapper.findSnapshots(projectId, locale, format, limit).stream().map(this::snapshot).toList();
    }

    public List<ReleaseLine> lines(long projectId, String locale) {
        return mapper.findLines(projectId, locale).stream()
                .map(row -> new ReleaseLine(row.getEntryId(), row.getEntryKey(), row.getSourceText(),
                        row.getSourceRevision(), row.getTranslation(), row.getTranslationStatus(),
                        row.getTranslationSourceRevision(), row.getTranslationVersion())).toList();
    }

    @Override
    public Optional<ReleaseJob> findJob(long projectId, long jobId) {
        return Optional.ofNullable(mapper.findJob(projectId, jobId)).map(this::job);
    }

    @Override
    public Optional<ReleaseJob> findJobForUpdate(long projectId, long jobId) {
        return Optional.ofNullable(mapper.findJobForUpdate(projectId, jobId)).map(this::job);
    }

    @Override
    public Optional<ReleaseJob> findJobByKey(long projectId, long actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findJobByKey(projectId, actorId, idempotencyKey)).map(this::job);
    }

    @Override
    public List<ReleaseJob> findQueuedJobs(int limit) {
        return mapper.findQueuedJobs(limit).stream().map(this::job).toList();
    }

    @Override
    public List<ReleaseJob> listJobs(long projectId, String locale, int limit) {
        return mapper.listJobs(projectId, locale, limit).stream().map(this::job).toList();
    }

    @Override
    public void insertJob(ReleaseJob job) {
        var row = new ReleaseMapper.ReleaseJobRow();
        row.setId(job.id());
        row.setProjectId(job.projectId());
        row.setLocale(job.locale());
        row.setFormat(job.format());
        row.setIdempotencyKey(job.idempotencyKey());
        row.setRequestHash(job.requestHash());
        row.setStatus(job.status());
        row.setCreatedBy(job.createdBy());
        row.setCreatedAt(job.createdAt());
        row.setUpdatedAt(job.updatedAt());
        mapper.insertJob(row);
    }

    @Override
    public boolean claimJob(long jobId, Instant updatedAt) {
        return mapper.claimJob(jobId, updatedAt) == 1;
    }

    @Override
    public boolean completeJob(long jobId, long releaseId, Instant updatedAt) {
        return mapper.completeJob(jobId, releaseId, updatedAt) == 1;
    }

    @Override
    public boolean failJob(long jobId, String error, Instant updatedAt) {
        return mapper.failJob(jobId, error, updatedAt) == 1;
    }

    @Override
    public boolean retryJob(long projectId, long jobId, Instant updatedAt) {
        return mapper.retryJob(projectId, jobId, updatedAt) == 1;
    }

    @Override
    public int recoverStaleJobs(Instant runningBefore, Instant updatedAt) {
        return mapper.recoverStaleJobs(runningBefore, updatedAt);
    }

    private ReleaseSnapshot snapshot(ReleaseMapper.ReleaseRow row) {
        return new ReleaseSnapshot(row.getId(), row.getProjectId(), row.getLocale(), row.getFormat(),
                row.getVersionNo(), row.getRollbackOfReleaseId(), row.getContent(),
                row.getManifestJson(), row.getChecksumSha256(),
                row.getPublishedBy(), row.getPublishedAt());
    }

    private ReleaseJob job(ReleaseMapper.ReleaseJobRow row) {
        return new ReleaseJob(row.getId(), row.getProjectId(), row.getLocale(), row.getFormat(),
                row.getIdempotencyKey(), row.getRequestHash(), row.getStatus(), row.getReleaseId(),
                row.getError(), row.getCreatedBy(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
