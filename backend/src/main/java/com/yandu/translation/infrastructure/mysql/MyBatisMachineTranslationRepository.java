package com.yandu.translation.infrastructure.mysql;

import com.yandu.translation.application.MachineTranslationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisMachineTranslationRepository implements MachineTranslationRepository {

    private final MachineTranslationMapper mapper;

    public MyBatisMachineTranslationRepository(MachineTranslationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public JobCreation create(JobDraft draft) {
        if (mapper.lockActiveTargetLocale(draft.projectId(), draft.locale()) == null) {
            throw new IllegalStateException("Target locale is no longer active");
        }
        var existing = mapper.findByIdempotencyKeyForUpdate(draft.projectId(), draft.actorId(), draft.idempotencyKey());
        if (existing != null) return new JobCreation(toJob(existing), false);

        mapper.insertJobIfAbsent(draft.id(), draft.projectId(), draft.sourceLocale(), draft.locale(),
                draft.idempotencyKey(), draft.requestHash(), draft.actorId(), draft.createdAt());
        // A locking read sees a concurrent INSERT IGNORE winner despite MySQL REPEATABLE READ snapshots.
        var stored = mapper.findByIdempotencyKeyForUpdate(draft.projectId(), draft.actorId(), draft.idempotencyKey());
        if (stored == null) throw new IllegalStateException("Machine translation job could not be created");
        if (stored.getId() != draft.id()) return new JobCreation(toJob(stored), false);

        mapper.insertEligibleItems(draft.id(), draft.projectId(), draft.locale(),
                draft.entryIds(), draft.createdAt());
        mapper.setInitialTotal(draft.id(), draft.createdAt());
        mapper.completeEmptyJob(draft.id(), draft.createdAt());
        return new JobCreation(toJob(mapper.findJob(draft.projectId(), draft.id())), true);
    }

    @Override
    public Optional<Job> find(long projectId, long jobId) {
        return Optional.ofNullable(mapper.findJob(projectId, jobId)).map(MyBatisMachineTranslationRepository::toJob);
    }

    @Override
    public Optional<Job> findByIdempotencyKey(long projectId, long actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findByIdempotencyKey(projectId, actorId, idempotencyKey))
                .map(MyBatisMachineTranslationRepository::toJob);
    }

    @Override
    public List<Job> list(long projectId, int limit) {
        return mapper.findJobs(projectId, limit).stream().map(MyBatisMachineTranslationRepository::toJob).toList();
    }

    @Override public long countActiveEntries(long projectId, List<Long> entryIds) {
        return mapper.countActiveEntries(projectId, entryIds);
    }

    @Override
    public List<JobItem> items(long projectId, long jobId, int offset, int limit) {
        return mapper.findItems(projectId, jobId, offset, limit).stream()
                .map(MyBatisMachineTranslationRepository::toItem).toList();
    }

    @Override public long itemCount(long projectId, long jobId) { return mapper.countItems(projectId, jobId); }

    @Override public List<Long> unfinishedJobIds() { return mapper.findUnfinishedJobIds(); }

    @Override
    @Transactional
    public int retryFailed(long projectId, long jobId, Instant updatedAt) {
        var changed = mapper.retryFailed(projectId, jobId, updatedAt);
        if (changed > 0) mapper.refreshJob(jobId, updatedAt);
        return changed;
    }

    @Override
    @Transactional
    public void recoverStale(Instant staleBefore, Instant updatedAt) {
        mapper.recoverStaleItems(staleBefore, updatedAt);
        for (var jobId : mapper.findUnfinishedJobIds()) mapper.refreshJob(jobId, updatedAt);
    }

    @Override
    @Transactional
    public Optional<JobItem> claimNext(Instant updatedAt) {
        var item = mapper.findNextQueuedItem();
        if (item == null || mapper.claimItem(item.getJobId(), item.getEntryId(), updatedAt) != 1) {
            return Optional.empty();
        }
        mapper.refreshJob(item.getJobId(), updatedAt);
        item.setAttempts(item.getAttempts() + 1);
        return Optional.of(toItem(item));
    }

    @Override
    @Transactional
    public void releaseClaim(JobItem item, Instant updatedAt) {
        if (mapper.releaseClaim(item.jobId(), item.entryId(), updatedAt) == 1) {
            mapper.refreshJob(item.jobId(), updatedAt);
        }
    }

    @Override
    @Transactional
    public void completeItem(long jobId, long entryId, String status, String message, Instant updatedAt) {
        if (mapper.completeItem(jobId, entryId, status, message, updatedAt) != 1) {
            throw new IllegalStateException("Machine translation item state changed concurrently");
        }
        mapper.refreshJob(jobId, updatedAt);
    }

    private static Job toJob(MachineTranslationMapper.JobRow row) {
        return new Job(row.getId(), row.getProjectId(), row.getTargetLocale(), row.getStatus(),
                row.getTotalCount(), row.getCompletedCount(), row.getSkippedCount(), row.getFailedCount(),
                row.getCreatedBy(), row.getRequestHash(), row.getCreatedAt(), row.getUpdatedAt(), row.getError());
    }

    private static JobItem toItem(MachineTranslationMapper.ItemRow row) {
        return new JobItem(row.getJobId(), row.getProjectId(), row.getSourceLocale(), row.getTargetLocale(), row.getEntryId(),
                row.getEntryKey(), row.getSourceText(), row.getSourceRevision(),
                row.getExpectedTranslationVersion(), row.getActorId(), row.getAttempts(),
                row.getStatus(), row.getError());
    }
}
