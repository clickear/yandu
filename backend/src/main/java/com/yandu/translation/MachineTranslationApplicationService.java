package com.yandu.translation;

import com.yandu.audit.AuditLog;
import com.yandu.translation.TranslationApplicationService.MachineDraft;
import com.yandu.translation.TranslationApplicationService.MachineDraftResult;
import com.yandu.translation.application.MachineTranslationProvider;
import com.yandu.translation.application.MachineTranslationProvider.ProviderFailureException;
import com.yandu.translation.application.MachineTranslationRepository;
import com.yandu.translation.application.MachineTranslationRepository.Job;
import com.yandu.translation.application.MachineTranslationRepository.JobDraft;
import com.yandu.translation.application.MachineTranslationRepository.JobItem;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates durable provider work and delegates guarded draft changes to the Translation module. */
@Service
public class MachineTranslationApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MachineTranslationApplicationService.class);

    private static final int MAX_SELECTED_ENTRIES = 10_000;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final MachineTranslationRepository jobs;
    private final MachineTranslationProvider provider;
    private final TranslationApplicationService translations;
    private final WorkspaceApplicationService workspace;
    private final ProjectAccessPolicy access;
    private final java.util.function.LongSupplier ids;
    private final Clock clock;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final TaskExecutor executor;

    public MachineTranslationApplicationService(MachineTranslationRepository jobs,
                                                MachineTranslationProvider provider,
                                                TranslationApplicationService translations,
                                                WorkspaceApplicationService workspace,
                                                ProjectAccessPolicy access,
                                                java.util.function.LongSupplier ids,
                                                Clock clock, AuditLog audit,
                                                TransactionTemplate transactions,
                                                @Qualifier("machineTranslationTaskExecutor") TaskExecutor executor) {
        this.jobs = Objects.requireNonNull(jobs);
        this.provider = Objects.requireNonNull(provider);
        this.translations = Objects.requireNonNull(translations);
        this.workspace = Objects.requireNonNull(workspace);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
        this.transactions = Objects.requireNonNull(transactions);
        this.executor = Objects.requireNonNull(executor);
    }

    @Transactional
    public JobView create(CreateJob command) {
        access.requireCanManageTranslation(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        var locale = WorkspaceApplicationService.normalizeLocale(command.locale());
        if (!project.targetLocales().contains(locale)) {
            throw new TranslationApplicationService.TargetLocaleNotConfiguredException(locale);
        }
        var entryIds = normalizeEntryIds(command.entryIds());
        if (!entryIds.isEmpty() && jobs.countActiveEntries(command.projectId(), entryIds) != entryIds.size()) {
            throw new IllegalArgumentException("词条选择包含不属于该项目或已归档的词条。");
        }
        var requestHash = requestHash(locale, entryIds);
        var existing = jobs.findByIdempotencyKey(command.projectId(), command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().requestHash())) {
                throw new IdempotencyConflictException(command.idempotencyKey());
            }
            return view(existing.get());
        }
        if (!provider.isConfigured()) throw new ProviderNotConfiguredException();

        var now = clock.instant();
        var created = jobs.create(new JobDraft(ids.getAsLong(), command.projectId(),
                project.sourceLocale(), locale, command.actorId(), command.idempotencyKey(),
                requestHash, entryIds, now));
        if (!requestHash.equals(created.job().requestHash())) {
            throw new IdempotencyConflictException(command.idempotencyKey());
        }
        if (created.created()) {
            audit.record(command.projectId(), command.actorId(), "MACHINE_TRANSLATION_QUEUED",
                    "MACHINE_TRANSLATION_JOB", created.job().id(), java.util.Map.of(
                            "locale", locale, "entryCount", created.job().totalCount()));
        }
        return view(created.job());
    }

    @Transactional(readOnly = true)
    public List<JobView> list(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        return jobs.list(projectId, 50).stream().map(MachineTranslationApplicationService::view).toList();
    }

    @Transactional(readOnly = true)
    public JobView get(long actorId, long projectId, long jobId) {
        access.requireCanReadProject(actorId, projectId);
        return view(find(projectId, jobId));
    }

    @Transactional(readOnly = true)
    public JobItemPage items(long actorId, long projectId, long jobId, int page, int pageSize) {
        access.requireCanReadProject(actorId, projectId);
        find(projectId, jobId);
        int offset = Math.multiplyExact(page - 1, pageSize);
        var items = jobs.items(projectId, jobId, offset, pageSize).stream()
                .map(item -> new JobItemView(Long.toString(item.entryId()),
                        Long.toString(item.namespaceId()), item.entryKey(),
                        item.status(), item.attempts(), item.error())).toList();
        return new JobItemPage(page, pageSize, jobs.itemCount(projectId, jobId), items);
    }

    @Transactional
    public JobView retry(RetryJob command) {
        access.requireCanManageTranslation(command.actorId(), command.projectId());
        var job = find(command.projectId(), command.jobId());
        if (job.failedCount() == 0
                || jobs.retryFailed(command.projectId(), command.jobId(), clock.instant()) == 0) {
            throw new JobStateConflictException(command.jobId());
        }
        audit.record(command.projectId(), command.actorId(), "MACHINE_TRANSLATION_RETRIED",
                "MACHINE_TRANSLATION_JOB", command.jobId(), java.util.Map.of());
        return view(find(command.projectId(), command.jobId()));
    }

    /** Persistent rows remain the queue of record; one worker claims and runs one item per poll. */
    @Scheduled(fixedDelayString = "${yandu.machine-translation.poll-ms:1000}")
    public void dispatchQueuedItems() {
        if (!provider.isConfigured()) return;
        var now = clock.instant();
        jobs.recoverStale(now.minus(Duration.ofMinutes(10)), now);
        for (var slot = 0; slot < 4; slot++) {
            var item = jobs.claimNext(clock.instant()).orElse(null);
            if (item == null) return;
            try {
                executor.execute(() -> processItem(item));
            } catch (TaskRejectedException rejected) {
                jobs.releaseClaim(item, clock.instant());
                return;
            }
        }
    }

    private void processItem(JobItem item) {
        final String translated;
        try {
            translated = provider.translate(item.sourceText(), item.sourceLocale(), item.locale());
        } catch (ProviderFailureException failure) {
            finish(item, "FAILED", safeError(failure.getMessage()));
            return;
        } catch (RuntimeException failure) {
            finish(item, "FAILED", "机器翻译服务调用失败，请稍后重试。");
            return;
        }

        try {
            transactions.executeWithoutResult(transaction -> {
                // attempts is the lease generation for accepted work. A worker whose lease
                // expired while the provider was responding must never persist its late result.
                if (!jobs.lockCurrentClaim(item)) return;
                var result = translations.saveMachineDraft(new MachineDraft(item.actorId(), item.projectId(),
                        item.entryId(), item.locale(), item.sourceRevision(),
                        item.expectedTranslationVersion(), translated));
                if (result == MachineDraftResult.APPLIED) {
                    if (!jobs.completeItem(item.jobId(), item.entryId(), item.attempts(),
                            "COMPLETED", null, clock.instant())) {
                        throw new IllegalStateException("Machine translation claim changed while locked");
                    }
                } else {
                    var message = result == MachineDraftResult.SOURCE_CHANGED
                            ? "任务创建后源文案已更新，旧译文未回填。"
                            : "任务创建后译文已修改，自动结果未覆盖当前内容。";
                    if (!jobs.completeItem(item.jobId(), item.entryId(), item.attempts(),
                            "SKIPPED", message, clock.instant())) {
                        throw new IllegalStateException("Machine translation claim changed while locked");
                    }
                }
            });
        } catch (AccessDeniedException | SecurityException accessRevoked) {
            finish(item, "FAILED", "发起人的项目翻译权限已变化，请由当前成员重新发起任务。");
        } catch (DataIntegrityViolationException concurrentEdit) {
            finish(item, "SKIPPED", "译文已被其他操作创建，自动结果未覆盖当前内容。");
        } catch (RuntimeException failure) {
            LOGGER.warn("Machine translation result persistence failed (jobId={}, entryId={}, errorType={})",
                    item.jobId(), item.entryId(), failure.getClass().getSimpleName());
            finish(item, "FAILED", "机器翻译结果未能保存，请检查任务状态后重试。");
        }
    }

    private void finish(JobItem item, String status, String message) {
        // A stale provider failure is also fenced; it cannot mark a newer attempt failed.
        jobs.completeItem(item.jobId(), item.entryId(), item.attempts(), status, message, clock.instant());
    }

    private Job find(long projectId, long jobId) {
        return jobs.find(projectId, jobId).orElseThrow(() -> new JobNotFoundException(jobId));
    }

    private static List<Long> normalizeEntryIds(List<Long> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_SELECTED_ENTRIES || values.stream().anyMatch(id -> id == null || id <= 0)
                || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("词条选择无效或超过 10,000 条限制。");
        }
        return values.stream().sorted().toList();
    }

    private static String requestHash(String locale, List<Long> entryIds) {
        var scope = locale + "\n" + (entryIds.isEmpty() ? "ALL_UNTRANSLATED" :
                entryIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return "机器翻译服务调用失败，请稍后重试。";
        return value.substring(0, Math.min(value.length(), MAX_ERROR_LENGTH));
    }

    private static JobView view(Job job) {
        return new JobView(Long.toString(job.id()), Long.toString(job.projectId()), job.locale(),
                job.status(), job.totalCount(), job.completedCount(), job.skippedCount(),
                job.failedCount(), job.createdAt(), job.updatedAt(), job.error());
    }

    public record CreateJob(long actorId, long projectId, String locale, String idempotencyKey,
                            List<Long> entryIds) {
        public CreateJob {
            if (actorId <= 0 || projectId <= 0) throw new IllegalArgumentException("actorId/projectId must be positive");
            if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,100}")) {
                throw new IllegalArgumentException("Idempotency-Key must contain 8 to 100 safe characters");
            }
        }
    }

    public record RetryJob(long actorId, long projectId, long jobId) { }
    public record JobView(String id, String projectId, String locale, String status, long totalCount,
                          long completedCount, long skippedCount, long failedCount,
                          java.time.Instant createdAt, java.time.Instant updatedAt, String error) { }
    public record JobItemView(String entryId, String namespaceId, String key, String status,
                              int attempts, String error) { }
    public record JobItemPage(int page, int pageSize, long total, List<JobItemView> items) { }

    public static final class ProviderNotConfiguredException extends RuntimeException {
        public ProviderNotConfiguredException() {
            super("机器翻译服务尚未配置，请检查服务端 YANDU_MT_PROVIDER、YANDU_MT_BASE_URL；"
                    + "使用大模型适配器时还需设置 YANDU_MT_MODEL。");
        }
    }
    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) { super("幂等键已用于不同的机器翻译请求：" + key); }
    }
    public static final class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(long id) { super("机器翻译任务不存在：" + id); }
    }
    public static final class JobStateConflictException extends RuntimeException {
        public JobStateConflictException(long id) { super("机器翻译任务没有可重试的失败项：" + id); }
    }
}
