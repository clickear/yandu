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

    @Override
    @Transactional
    public int retryFailed(long projectId, long jobId, Instant updatedAt) {
        requireJobLock(jobId);
        var changed = mapper.retryFailed(projectId, jobId, updatedAt);
        if (changed > 0) mapper.refreshJob(jobId, updatedAt);
        return changed;
    }

    @Override
    @Transactional
    public void recoverStale(Instant staleBefore, Instant updatedAt) {
        // Always lock a job before any of its item rows, matching claim/retry/complete transitions.
        for (var jobId : mapper.findStaleJobIds(staleBefore)) {
            if (mapper.lockJobForItemTransition(jobId) == null) continue;
            if (mapper.recoverStaleItems(jobId, staleBefore, updatedAt) > 0) {
                mapper.refreshJob(jobId, updatedAt);
            }
        }
    }

    @Override
    @Transactional
    public Optional<JobItem> claimNext(Instant updatedAt) {
        var item = mapper.findNextQueuedItem();
        if (item == null) return Optional.empty();
        if (mapper.lockJobForItemTransition(item.getJobId()) == null
                || mapper.claimItem(item.getJobId(), item.getEntryId(), updatedAt) != 1) {
            return Optional.empty();
        }
        mapper.refreshJob(item.getJobId(), updatedAt);
        item.setAttempts(item.getAttempts() + 1);
        return Optional.of(toItem(item));
    }

    @Override
    @Transactional
    public void releaseClaim(JobItem item, Instant updatedAt) {
        if (mapper.lockJobForItemTransition(item.jobId()) == null) return;
        if (mapper.releaseClaim(item.jobId(), item.entryId(), item.attempts(), updatedAt) == 1) {
            mapper.refreshJob(item.jobId(), updatedAt);
        }
    }

    @Override
    @Transactional
    public boolean lockCurrentClaim(JobItem item) {
        if (mapper.lockJobForItemTransition(item.jobId()) == null) return false;
        return mapper.lockCurrentClaim(item.jobId(), item.entryId(), item.attempts()) != null;
    }

    @Override
    @Transactional
    public boolean completeItem(long jobId, long entryId, int attempt, String status, String message, Instant updatedAt) {
        // Lock the parent first so parallel workers recalculate one job's counters in a stable order.
        if (mapper.lockJobForItemTransition(jobId) == null) return false;
        if (mapper.completeItem(jobId, entryId, attempt, status, message, updatedAt) != 1) return false;
        mapper.refreshJob(jobId, updatedAt);
        return true;
    }

    private void requireJobLock(long jobId) {
        if (mapper.lockJobForItemTransition(jobId) == null) {
            throw new IllegalStateException("Machine translation job no longer exists");
        }
    }

    private static Job toJob(MachineTranslationMapper.JobRow row) {
        return new Job(row.getId(), row.getProjectId(), row.getTargetLocale(), row.getStatus(),
                row.getTotalCount(), row.getCompletedCount(), row.getSkippedCount(), row.getFailedCount(),
                row.getCreatedBy(), row.getRequestHash(), row.getCreatedAt(), row.getUpdatedAt(), row.getError());
    }

    private static JobItem toItem(MachineTranslationMapper.ItemRow row) {
        return new JobItem(row.getJobId(), row.getProjectId(), row.getSourceLocale(), row.getTargetLocale(), row.getEntryId(),
                row.getNamespaceId(),
                row.getEntryKey(), row.getSourceText(), row.getSourceRevision(),
                row.getExpectedTranslationVersion(), row.getActorId(), row.getAttempts(),
                row.getStatus(), row.getError());
    }
}
