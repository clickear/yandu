package com.yandu.release;

import com.yandu.audit.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.release.application.ReleaseRepository;
import com.yandu.release.application.ReleaseRepository.ReleaseLine;
import com.yandu.release.application.ReleaseRepository.ReleaseSnapshot;
import com.yandu.release.application.ReleaseRepository.ReleaseJob;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds, stores and reads immutable localization release snapshots. */
@Service
public class ReleaseApplicationService {

    public static final String JSON_FLAT = "json-flat-v1";
    public static final String JAVA_PROPERTIES = "java-properties-v1";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.-]*)}");
    private final ReleaseRepository releases;
    private final WorkspaceApplicationService workspace;
    private final ProjectAccessPolicy access;
    private final LongSupplier ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final Set<Long> scheduledJobs = ConcurrentHashMap.newKeySet();

    public ReleaseApplicationService(ReleaseRepository releases, WorkspaceApplicationService workspace,
                                    ProjectAccessPolicy access, LongSupplier ids, Clock clock,
                                    ObjectMapper json, AuditLog audit,
                                    @Qualifier("importTaskExecutor") TaskExecutor executor,
                                    PlatformTransactionManager transactionManager) {
        this.releases = Objects.requireNonNull(releases);
        this.workspace = Objects.requireNonNull(workspace);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.audit = Objects.requireNonNull(audit);
        this.executor = Objects.requireNonNull(executor);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Transactional
    public ReleaseJobView requestPublish(PublishRelease command, String idempotencyKey) {
        access.requireCanPublish(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        requireActiveProject(project);
        var locale = WorkspaceApplicationService.normalizeLocale(command.locale());
        if (!project.targetLocales().contains(locale)) {
            throw new IllegalArgumentException("Release locale is not configured for this project");
        }
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("A valid Idempotency-Key header is required");
        }
        var hash = sha256(locale + "\n" + command.format());
        var existing = releases.findJobByKey(command.projectId(), command.actorId(), idempotencyKey);
        if (existing.isPresent()) return existing(existing.get(), hash);
        var now = clock.instant();
        var job = new ReleaseJob(ids.getAsLong(), command.projectId(), locale, command.format(),
                idempotencyKey, hash, "QUEUED", null, null, command.actorId(), now, now);
        try {
            releases.insertJob(job);
        } catch (DataIntegrityViolationException duplicate) {
            var winner = releases.findJobByKey(command.projectId(), command.actorId(), idempotencyKey)
                    .orElseThrow(() -> duplicate);
            return existing(winner, hash);
        }
        audit.record(command.projectId(), command.actorId(), "RELEASE_QUEUED", "RELEASE_JOB",
                job.id(), java.util.Map.of("locale", locale, "format", command.format()));
        return jobView(job);
    }

    private static ReleaseJobView existing(ReleaseJob job, String requestHash) {
        if (!job.requestHash().equals(requestHash)) throw new ReleaseIdempotencyConflictException();
        return jobView(job);
    }

    @Transactional(readOnly = true)
    public ReleaseJobView getJob(long actorId, long projectId, long jobId) {
        access.requireCanReadProject(actorId, projectId);
        return jobView(releases.findJob(projectId, jobId)
                .orElseThrow(() -> new ReleaseJobNotFoundException(jobId)));
    }

    @Transactional(readOnly = true)
    public List<ReleaseJobView> listJobs(long actorId, long projectId, String locale) {
        access.requireCanReadProject(actorId, projectId);
        var normalizedLocale = locale == null ? null : WorkspaceApplicationService.normalizeLocale(locale);
        return releases.listJobs(projectId, normalizedLocale, 50).stream().map(ReleaseApplicationService::jobView)
                .toList();
    }

    @Transactional
    public ReleaseJobView retryJob(long actorId, long projectId, long jobId) {
        access.requireCanPublish(actorId, projectId);
        requireActiveProject(workspace.getProject(actorId, projectId));
        var job = releases.findJob(projectId, jobId)
                .orElseThrow(() -> new ReleaseJobNotFoundException(jobId));
        if (!"FAILED".equals(job.status()) || !releases.retryJob(projectId, jobId, clock.instant())) {
            throw new ReleaseJobStateConflictException(jobId);
        }
        audit.record(projectId, actorId, "RELEASE_RETRIED", "RELEASE_JOB", jobId,
                java.util.Map.of("locale", job.locale(), "format", job.format()));
        return jobView(releases.findJob(projectId, jobId).orElseThrow());
    }

    /** A durable database queue remains authoritative across application restarts. */
    @Scheduled(fixedDelayString = "${yandu.release.poll-ms:1000}")
    public void dispatchQueuedJobs() {
        var now = clock.instant();
        releases.recoverStaleJobs(now.minus(Duration.ofMinutes(30)), now);
        for (var job : releases.findQueuedJobs(20)) {
            if (!scheduledJobs.add(job.id())) continue;
            try {
                executor.execute(() -> {
                    try { processJob(job); }
                    finally { scheduledJobs.remove(job.id()); }
                });
            } catch (RuntimeException rejected) {
                scheduledJobs.remove(job.id());
            }
        }
    }

    private void processJob(ReleaseJob queued) {
        boolean claimed = Boolean.TRUE.equals(transactions.execute(status ->
                releases.claimJob(queued.id(), clock.instant())));
        if (!claimed) return;
        try {
            transactions.executeWithoutResult(status -> {
                var job = releases.findJobForUpdate(queued.projectId(), queued.id())
                        .orElseThrow(() -> new ReleaseJobNotFoundException(queued.id()));
                var snapshot = publish(new PublishRelease(job.createdBy(), job.projectId(),
                        job.locale(), job.format()));
                if (!releases.completeJob(job.id(), Long.parseLong(snapshot.id()), clock.instant())) {
                    throw new ReleaseJobStateConflictException(job.id());
                }
            });
        } catch (RuntimeException failure) {
            var error = failure instanceof ReleaseGateException gate
                    ? String.join("\n", gate.failures())
                    : "发布任务执行失败，请稍后重试。";
            if (error.length() > 2000) error = error.substring(0, 2000);
            var safeError = error;
            transactions.executeWithoutResult(status -> {
                if (releases.failJob(queued.id(), safeError, clock.instant())) {
                    audit.record(queued.projectId(), queued.createdBy(), "RELEASE_FAILED", "RELEASE_JOB",
                            queued.id(), java.util.Map.of("error", safeError));
                }
            });
        }
    }

    @Transactional
    public ReleaseView publish(PublishRelease command) {
        access.requireCanPublish(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        requireActiveProject(project);
        var locale = WorkspaceApplicationService.normalizeLocale(command.locale());
        var format = normalizeFormat(command.format());
        if (!project.targetLocales().contains(locale)) {
            throw new IllegalArgumentException("Release locale is not configured for this project");
        }

        var lines = releases.lines(command.projectId(), locale);
        if (lines.isEmpty()) throw new ReleaseGateException(List.of("项目没有可发布的资源条目"));
        var content = new TreeMap<String, String>();
        var failures = new java.util.ArrayList<String>();
        var manifestEntries = new java.util.ArrayList<ManifestEntry>();
        for (var line : lines) {
            validate(line, locale, failures);
            if (content.putIfAbsent(line.key(), line.translation()) != null) {
                failures.add("资源键在多个命名空间中重复: " + line.key());
            }
            manifestEntries.add(new ManifestEntry(Long.toString(line.entryId()), line.key(),
                    line.sourceRevision(), line.translationVersion()));
        }
        if (!failures.isEmpty()) throw new ReleaseGateException(failures);

        String snapshot;
        String manifest;
        try {
            snapshot = JSON_FLAT.equals(format) ? json.writeValueAsString(content) + "\n"
                    : properties(content);
            manifest = json.writeValueAsString(new ReleaseManifest(format, locale,
                    manifestEntries, List.of("PLACEHOLDER_MISMATCH")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize release snapshot", exception);
        }
        var checksum = sha256(snapshot);
        var stored = releases.create(ids.getAsLong(), command.projectId(), locale, format,
                snapshot, manifest, null, checksum, command.actorId(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "RELEASE_PUBLISHED", "RELEASE",
                stored.id(), java.util.Map.of("locale", locale, "format", format, "version", stored.version(),
                        "checksumSha256", checksum, "entryCount", content.size()));
        return view(stored, content.size());
    }

    private static void requireActiveProject(WorkspaceApplicationService.ProjectView project) {
        if (!"ACTIVE".equals(project.status())) {
            throw new IllegalStateException("归档项目不能创建新发布任务。");
        }
    }

    @Transactional
    public ReleaseView rollback(long actorId, long projectId, long releaseId) {
        access.requireCanPublish(actorId, projectId);
        workspace.getProject(actorId, projectId);
        var previous = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(previous);
        var stored = releases.create(ids.getAsLong(), projectId, previous.locale(), previous.format(),
                previous.content(), previous.manifest(), previous.id(), previous.checksum(),
                actorId, clock.instant());
        audit.record(projectId, actorId, "RELEASE_ROLLED_BACK", "RELEASE", stored.id(),
                java.util.Map.of("locale", stored.locale(), "version", stored.version(),
                        "rollbackOfReleaseId", previous.id(), "checksumSha256", stored.checksum()));
        return view(stored, countEntries(stored));
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> history(long actorId, long projectId, String locale, String format) {
        access.requireCanReadProject(actorId, projectId);
        var normalizedLocale = locale == null ? null : WorkspaceApplicationService.normalizeLocale(locale);
        var normalizedFormat = format == null ? null : normalizeFormat(format);
        return releases.list(projectId, normalizedLocale, normalizedFormat, 100).stream()
                .map(snapshot -> view(verify(snapshot), countEntries(snapshot)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReleaseSummary> historySummaries(long actorId, long projectId, String locale, String format) {
        // Keep large immutable artifact bodies out of release-history responses.
        return history(actorId, projectId, locale, format).stream()
                .map(ReleaseApplicationService::summary).toList();
    }

    public static ReleaseSummary summary(ReleaseView release) {
        return new ReleaseSummary(release.id(), release.projectId(), release.locale(), release.format(),
                release.version(), release.rollbackOfReleaseId(), release.checksumSha256(),
                release.entryCount(), release.publishedBy(), release.publishedAt());
    }

    @Transactional(readOnly = true)
    public ReleaseView get(long actorId, long projectId, long releaseId) {
        access.requireCanReadProject(actorId, projectId);
        var snapshot = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(snapshot);
        return view(snapshot, countEntries(snapshot));
    }

    @Transactional
    public String download(long actorId, long projectId, long releaseId) {
        access.requireCanReadProject(actorId, projectId);
        var snapshot = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(snapshot);
        audit.record(projectId, actorId, "RELEASE_DOWNLOADED", "RELEASE", releaseId,
                java.util.Map.of("locale", snapshot.locale(), "version", snapshot.version(),
                        "checksumSha256", snapshot.checksum()));
        return snapshot.content();
    }

    private static void validate(ReleaseLine line, String locale, List<String> failures) {
        if (!"APPROVED".equals(line.status())) {
            failures.add(line.key() + "（" + locale + "）：译文尚未审核通过");
            return;
        }
        if (line.translationSourceRevision() != line.sourceRevision()) {
            failures.add(line.key() + "（" + locale + "）：译文基于旧版源文");
            return;
        }
        if (!placeholders(line.sourceText()).equals(placeholders(line.translation()))) {
            failures.add(line.key() + "（" + locale + "）：命名占位符与源文不一致");
        }
    }

    private static java.util.Map<String, Integer> placeholders(String value) {
        var result = new java.util.TreeMap<String, Integer>();
        var matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) result.merge(matcher.group(1), 1, Integer::sum);
        return result;
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return JSON_FLAT;
        if (!JSON_FLAT.equals(format) && !JAVA_PROPERTIES.equals(format)) {
            throw new IllegalArgumentException("format must be json-flat-v1 or java-properties-v1");
        }
        return format;
    }

    /** Emits stable ASCII accepted by Java Properties.load(InputStream). */
    private static String properties(TreeMap<String, String> values) {
        var output = new StringBuilder();
        values.forEach((key, value) -> output.append(escapeProperty(key, true))
                .append('=').append(escapeProperty(value, false)).append('\n'));
        return output.toString();
    }

    private static String escapeProperty(String value, boolean key) {
        var output = new StringBuilder(value.length());
        boolean leadingValueSpace = !key;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ' ' && (key || leadingValueSpace)) output.append("\\ ");
            else appendPropertyChar(output, character, key);
            if (!key && character != ' ') leadingValueSpace = false;
        }
        return output.toString();
    }

    private static void appendPropertyChar(StringBuilder output, char character, boolean key) {
        switch (character) {
            case '\\' -> output.append("\\\\");
            case '\t' -> output.append("\\t");
            case '\n' -> output.append("\\n");
            case '\r' -> output.append("\\r");
            case '\f' -> output.append("\\f");
            case '=', ':' -> {
                if (key) output.append('\\');
                output.append(character);
            }
            case '#', '!' -> {
                if (key) output.append('\\');
                output.append(character);
            }
            default -> {
                if (character < 0x20 || character > 0x7e) {
                    output.append("\\u");
                    var hex = Integer.toHexString(character).toUpperCase(java.util.Locale.ROOT);
                    output.append("0".repeat(4 - hex.length())).append(hex);
                } else output.append(character);
            }
        }
    }

    private int countEntries(ReleaseSnapshot snapshot) {
        if (JAVA_PROPERTIES.equals(snapshot.format())) {
            return (int) snapshot.content().lines().filter(line -> !line.isBlank()).count();
        }
        try {
            return json.readTree(snapshot.content()).size();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored release snapshot is invalid", exception);
        }
    }

    private ReleaseSnapshot verify(ReleaseSnapshot snapshot) {
        if (!sha256(snapshot.content()).equals(snapshot.checksum())) {
            throw new IllegalStateException("Stored release checksum does not match its content");
        }
        return snapshot;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ReleaseView view(ReleaseSnapshot snapshot, int entryCount) {
        return new ReleaseView(Long.toString(snapshot.id()), Long.toString(snapshot.projectId()),
                snapshot.locale(), snapshot.format(), snapshot.version(), snapshot.rollbackOfReleaseId() == null
                        ? null : Long.toString(snapshot.rollbackOfReleaseId()),
                snapshot.content(), snapshot.manifest(),
                snapshot.checksum(), entryCount, Long.toString(snapshot.publishedBy()), snapshot.publishedAt());
    }

    private static ReleaseJobView jobView(ReleaseJob job) {
        return new ReleaseJobView(Long.toString(job.id()), Long.toString(job.projectId()), job.locale(),
                job.format(), job.status(), job.releaseId() == null ? null : Long.toString(job.releaseId()),
                job.error(), job.createdAt(), job.updatedAt());
    }

    public record PublishRelease(long actorId, long projectId, String locale, String format) {
        public PublishRelease(long actorId, long projectId, String locale) {
            this(actorId, projectId, locale, JSON_FLAT);
        }

        public PublishRelease {
            if (actorId <= 0 || projectId <= 0 || locale == null || locale.isBlank()) {
                throw new IllegalArgumentException("actorId, projectId and locale are required");
            }
            format = normalizeFormat(format);
        }
    }

    public record ReleaseView(String id, String projectId, String locale, String format, long version,
                              String rollbackOfReleaseId,
                              String content, String manifestJson, String checksumSha256, int entryCount,
                              String publishedBy, java.time.Instant publishedAt) { }

    public record ReleaseSummary(String id, String projectId, String locale, String format, long version,
                                 String rollbackOfReleaseId, String checksumSha256, int entryCount,
                                 String publishedBy, java.time.Instant publishedAt) { }

    public record ReleaseJobView(String id, String projectId, String locale, String format,
                                 String status, String releaseId, String error,
                                 java.time.Instant createdAt, java.time.Instant updatedAt) { }

    public record ReleaseManifest(String format, String locale, List<ManifestEntry> entries,
                                  List<String> passedQualityRules) { }

    public record ManifestEntry(String entryId, String key, long sourceRevision,
                                long translationVersion) { }

    public static final class ReleaseGateException extends RuntimeException {
        private final List<String> failures;
        public ReleaseGateException(List<String> failures) {
            super("Release quality gate failed");
            this.failures = List.copyOf(failures);
        }
        public List<String> failures() { return failures; }
    }

    public static final class ReleaseNotFoundException extends RuntimeException {
        public ReleaseNotFoundException(long releaseId) {
            super("Release not found: " + releaseId);
        }
    }

    public static final class ReleaseJobNotFoundException extends RuntimeException {
        public ReleaseJobNotFoundException(long jobId) { super("Release job was not found: " + jobId); }
    }

    public static final class ReleaseJobStateConflictException extends RuntimeException {
        public ReleaseJobStateConflictException(long jobId) { super("Release job state changed: " + jobId); }
    }

    public static final class ReleaseIdempotencyConflictException extends RuntimeException {
        public ReleaseIdempotencyConflictException() {
            super("This Idempotency-Key was already used for a different release request");
        }
    }
}
