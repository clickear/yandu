package com.yandu.imports.infrastructure.mysql;

import com.yandu.imports.application.ImportRepository;
import com.yandu.imports.application.ImportRepository.Job;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisImportRepository implements ImportRepository {
    private final ImportMapper mapper;

    public MyBatisImportRepository(ImportMapper mapper) { this.mapper = mapper; }

    @Override public Optional<Job> find(long projectId, long jobId) {
        return Optional.ofNullable(mapper.find(projectId, jobId)).map(this::job);
    }
    @Override public Optional<Job> findByIdempotencyKey(long projectId, String key) {
        return Optional.ofNullable(mapper.findByKey(projectId, key)).map(this::job);
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
        mapper.insertJob(row);
    }
    @Override public boolean queue(long projectId, long jobId, String hash, Instant at) {
        return mapper.queue(projectId, jobId, hash, at) == 1;
    }
    @Override public boolean claim(long jobId, Instant at) { return mapper.claim(jobId, at) == 1; }
    @Override public void complete(long jobId, Instant at) { mapper.complete(jobId, at); }
    @Override public boolean fail(long jobId, String error, Instant at) {
        return mapper.fail(jobId, error, at) == 1;
    }

    private Job job(ImportMapper.ImportJobRow row) {
        return new Job(row.getId(), row.getProjectId(), row.getNamespaceId(), row.getLocale(),
                row.getProjectVersion(), row.getIdempotencyKey(), row.getPayloadJson(),
                row.getPreviewJson(), row.getPreviewHash(), row.getStatus(), row.getCreatedBy(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getError());
    }
}
