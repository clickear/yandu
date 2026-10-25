package com.yandu.imports;

import com.yandu.audit.AuditLog;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.catalog.CatalogEntryApplicationService.CreateEntry;
import com.yandu.catalog.CatalogEntryApplicationService.SearchEntries;
import com.yandu.imports.application.ImportRepository;
import com.yandu.imports.application.ImportRepository.Job;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates strict JSON import previews, idempotency, queueing and asynchronous application. */
@Service
public class ImportApplicationService {

    public static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    public static final int MAX_IMPORT_ENTRIES = 10_000;
    private static final Pattern RESERVED_KEY = Pattern.compile("(^|\\.)(__proto__|prototype|constructor)(\\.|$)");
    private final ImportRepository imports;
    private final CatalogEntryApplicationService catalog;
    private final TranslationApplicationService translations;
    private final WorkspaceApplicationService workspace;
    private final ProjectAccessPolicy access;
    private final LongSupplier ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;
    private final AuditLog audit;
    private final java.util.Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    public ImportApplicationService(ImportRepository imports, CatalogEntryApplicationService catalog,
                                    TranslationApplicationService translations,
                                    WorkspaceApplicationService workspace, ProjectAccessPolicy access,
                                    LongSupplier ids, Clock clock, ObjectMapper json,
                                    TaskExecutor executor, PlatformTransactionManager transactionManager,
                                    AuditLog audit) {
        this.imports = Objects.requireNonNull(imports);
        this.catalog = Objects.requireNonNull(catalog);
        this.translations = Objects.requireNonNull(translations);
        this.workspace = Objects.requireNonNull(workspace);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.executor = Objects.requireNonNull(executor);
        this.transactions = new TransactionTemplate(transactionManager);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public ImportJobView preview(CreatePreview command) {
        access.requireCanManageCatalog(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        var locale = WorkspaceApplicationService.normalizeLocale(command.locale());
        if (!locale.equals(project.sourceLocale()) && !project.targetLocales().contains(locale)) {
            throw new IllegalArgumentException("Import locale is not configured for this project");
        }
        if (!Long.toString(command.namespaceId()).equals(project.defaultNamespaceId())) {
            throw new IllegalArgumentException("Import namespace does not belong to this project");
        }
        var payload = parsePayload(command.json());
        var payloadJson = serialize(payload);
        var existingJob = imports.findByIdempotencyKey(command.projectId(), command.idempotencyKey());
        if (existingJob.isPresent()) {
            var job = existingJob.get();
            if (!job.payloadJson().equals(payloadJson) || job.namespaceId() != command.namespaceId()
                    || !job.locale().equals(locale)) {
                throw new IdempotencyConflictException(command.idempotencyKey());
            }
            return view(job);
        }

        var entries = findCatalogEntries(command.actorId(), command.projectId(), command.namespaceId());
        var changes = new ArrayList<PreviewItem>(payload.size());
        for (var item : payload.entrySet()) {
            var entry = entries.get(item.getKey());
            if (locale.equals(project.sourceLocale())) {
                changes.add(sourceChange(item.getKey(), item.getValue(), entry));
            } else if (entry == null) {
                changes.add(new PreviewItem(item.getKey(), "CONFLICT", null, null,
                        item.getValue(), 0, 0, "目标语言导入的键必须先存在于源目录"));
            } else {
                var current = translations.findForImport(command.actorId(), command.projectId(),
                        Long.parseLong(entry.id()), locale);
                changes.add(translationChange(item.getKey(), item.getValue(), entry, current));
            }
        }
        var data = new PreviewData(locale, Long.toString(command.namespaceId()), project.version(),
                changes, count(changes, "ADD"), count(changes, "UPDATE"),
                count(changes, "UNCHANGED"), count(changes, "CONFLICT"));
        var previewJson = serialize(data);
        var now = clock.instant();
        var job = new Job(ids.getAsLong(), command.projectId(), command.namespaceId(), locale,
                project.version(), command.idempotencyKey(), payloadJson, previewJson,
                sha256(previewJson), "PREVIEW_READY", command.actorId(), now, now, null);
        imports.insert(job);
        audit.record(command.projectId(), command.actorId(), "IMPORT_PREVIEW_CREATED", "IMPORT_JOB",
                job.id(), Map.of("locale", locale, "added", data.added(), "updated", data.updated(),
                        "conflicts", data.conflicts()));
        return view(job);
    }

    @Transactional
    public ImportJobView apply(ApplyImport command) {
        access.requireCanManageCatalog(command.actorId(), command.projectId());
        var job = find(command.projectId(), command.jobId());
        if (!job.previewHash().equals(command.previewHash())) {
            throw new ImportPreviewConflictException("Preview hash does not match the saved preview");
        }
        var data = readPreview(job.previewJson());
        if (data.conflicts() > 0) throw new ImportPreviewConflictException("Resolve preview conflicts before applying");
        if ("PREVIEW_READY".equals(job.status())) {
            if (imports.queue(command.projectId(), command.jobId(), command.previewHash(), clock.instant())) {
                audit.record(command.projectId(), command.actorId(), "IMPORT_QUEUED", "IMPORT_JOB",
                        job.id(), Map.of("previewHash", job.previewHash()));
            }
        } else if (!"QUEUED".equals(job.status()) && !"RUNNING".equals(job.status())
                && !"COMPLETED".equals(job.status())) {
            throw new ImportPreviewConflictException("This import job cannot be applied in its current state");
        }
        return view(find(command.projectId(), command.jobId()));
    }

    @Transactional
    public ImportJobView retry(ApplyImport command) {
        access.requireCanManageCatalog(command.actorId(), command.projectId());
        var job = find(command.projectId(), command.jobId());
        if (!"FAILED".equals(job.status()) || !job.previewHash().equals(command.previewHash())) {
            throw new ImportPreviewConflictException("Only a failed job with the same preview can be retried");
        }
        var data = readPreview(job.previewJson());
        if (data.conflicts() > 0) throw new ImportPreviewConflictException("Resolve preview conflicts before retrying");
        if (!imports.queue(command.projectId(), command.jobId(), command.previewHash(), clock.instant())) {
            throw new ImportPreviewConflictException("Import job state changed; reload before retrying");
        }
        audit.record(command.projectId(), command.actorId(), "IMPORT_RETRIED", "IMPORT_JOB",
                job.id(), Map.of("previewHash", job.previewHash()));
        return view(find(command.projectId(), command.jobId()));
    }

    @Transactional(readOnly = true)
    public ImportJobView get(long actorId, long projectId, long jobId) {
        access.requireCanReadCatalog(actorId, projectId);
        return view(find(projectId, jobId));
    }

    @Scheduled(fixedDelayString = "${yandu.import.poll-ms:2000}")
    public void dispatchQueuedJobs() {
        var now = clock.instant();
        imports.recoverStale(now.minus(Duration.ofMinutes(30)), now);
        for (var job : imports.findQueued(20)) {
            if (!scheduled.add(job.id())) continue;
            try {
                executor.execute(() -> {
                    try { process(job.id(), job.projectId()); }
                    finally { scheduled.remove(job.id()); }
                });
            } catch (RuntimeException rejected) {
                scheduled.remove(job.id());
            }
        }
    }

    private void process(long jobId, long projectId) {
        if (!imports.claim(jobId, clock.instant())) return;
        try {
            transactions.executeWithoutResult(status -> applyInsideTransaction(projectId, jobId));
        } catch (RuntimeException failure) {
            var message = failure.getMessage() == null ? "Import failed" : failure.getMessage();
            var job = imports.find(projectId, jobId).orElse(null);
            var error = message.substring(0, Math.min(message.length(), 1000));
            transactions.executeWithoutResult(status -> {
                var failed = imports.fail(jobId, error, clock.instant());
                if (failed && job != null) audit.record(projectId, job.createdBy(), "IMPORT_FAILED", "IMPORT_JOB",
                        jobId, Map.of("error", error));
            });
        }
    }

    private void applyInsideTransaction(long projectId, long jobId) {
        var job = find(projectId, jobId);
        var project = workspace.getProject(job.createdBy(), projectId);
        if (project.version() != job.projectVersion()) {
            throw new ImportPreviewConflictException("Project settings changed after the preview was created");
        }
        var preview = readPreview(job.previewJson());
        var payload = parsePayload(job.payloadJson());
        for (var change : preview.items()) {
            if ("UNCHANGED".equals(change.action())) continue;
            var value = payload.get(change.key());
            if (value == null) throw new IllegalStateException("Import preview payload is incomplete");
            if (preview.locale().equals(project.sourceLocale())) {
                applySource(job, change, value);
            } else {
                applyTranslation(job, change, value, preview.locale());
            }
        }
        imports.complete(jobId, clock.instant());
        audit.record(projectId, job.createdBy(), "IMPORT_COMPLETED", "IMPORT_JOB", jobId,
                Map.of("entryCount", preview.items().size(), "locale", preview.locale()));
    }

    private void applySource(Job job, PreviewItem change, String value) {
        if ("ADD".equals(change.action())) {
            catalog.create(new CreateEntry(job.createdBy(), job.projectId(), job.namespaceId(),
                    job.locale(), change.key(), value));
            return;
        }
        var currentRevision = catalog.currentSourceRevision(job.createdBy(), job.projectId(),
                Long.parseLong(change.entryId()));
        if (currentRevision != change.expectedSourceRevision()) {
            throw new ImportPreviewConflictException("Source entry changed after preview: " + change.key());
        }
        catalog.changeSource(new CatalogEntryApplicationService.ChangeSource(job.createdBy(),
                job.projectId(), Long.parseLong(change.entryId()), change.expectedSourceRevision(), value));
    }

    private void applyTranslation(Job job, PreviewItem change, String value, String locale) {
        var entryId = Long.parseLong(change.entryId());
        var source = catalog.get(job.createdBy(), job.projectId(), entryId);
        if (source.sourceRevision() != change.expectedSourceRevision()) {
            throw new ImportPreviewConflictException("Source entry changed after preview: " + change.key());
        }
        var current = translations.findForImport(job.createdBy(), job.projectId(), entryId, locale);
        var currentVersion = current.map(TranslationApplicationService.TranslationView::version).orElse(0L);
        if (currentVersion != change.expectedTranslationVersion()) {
            throw new ImportPreviewConflictException("Translation changed after preview: " + change.key());
        }
        translations.save(new TranslationApplicationService.SaveTranslation(job.createdBy(),
                job.projectId(), entryId, locale, currentVersion, source.sourceRevision(), value));
    }

    private PreviewItem sourceChange(String key, String incoming, CatalogEntryApplicationService.EntryView entry) {
        if (incoming.isBlank()) {
            return new PreviewItem(key, "CONFLICT", entry == null ? null : entry.id(),
                    entry == null ? null : entry.sourceText(), incoming,
                    entry == null ? 0 : entry.sourceRevision(), 0, "源文案不能为空");
        }
        if (entry == null) return new PreviewItem(key, "ADD", null, null, incoming, 0, 0, null);
        var action = entry.sourceText().equals(incoming) ? "UNCHANGED" : "UPDATE";
        return new PreviewItem(key, action, entry.id(), entry.sourceText(), incoming,
                entry.sourceRevision(), 0, null);
    }

    private PreviewItem translationChange(String key, String incoming,
                                           CatalogEntryApplicationService.EntryView entry,
                                           Optional<TranslationApplicationService.TranslationView> translation) {
        var current = translation.map(TranslationApplicationService.TranslationView::value).orElse(null);
        var version = translation.map(TranslationApplicationService.TranslationView::version).orElse(0L);
        var needsRebase = translation.map(TranslationApplicationService.TranslationView::sourceIsNewer)
                .orElse(false);
        var action = Objects.equals(current, incoming) && !needsRebase
                ? "UNCHANGED" : current == null ? "ADD" : "UPDATE";
        return new PreviewItem(key, action, entry.id(), current, incoming,
                entry.sourceRevision(), version, null);
    }

    private Map<String, CatalogEntryApplicationService.EntryView> findCatalogEntries(
            long actorId, long projectId, long namespaceId) {
        var result = new TreeMap<String, CatalogEntryApplicationService.EntryView>();
        int page = 1;
        CatalogEntryApplicationService.EntryPage entries;
        do {
            entries = catalog.search(new SearchEntries(actorId, projectId, namespaceId,
                    null, page++, 100));
            for (var entry : entries.items()) result.put(entry.key(), entry);
        } while ((long) (page - 1) * 100 < entries.total());
        return result;
    }

    private TreeMap<String, String> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("JSON payload is required");
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("JSON import cannot exceed 5 MiB");
        }
        try (var parser = json.getFactory().createParser(payload)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = json.readTree(parser);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Import JSON must be a flat object");
            if (node.size() == 0 || node.size() > MAX_IMPORT_ENTRIES) {
                throw new IllegalArgumentException("JSON must contain between 1 and 10000 entries");
            }
            var values = new TreeMap<String, String>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                var key = field.getKey();
                var value = field.getValue();
                if (key.isBlank() || key.length() > 200 || !key.equals(key.strip())
                        || RESERVED_KEY.matcher(key).find()) {
                    throw new IllegalArgumentException("Import contains an invalid resource key");
                }
                if (!value.isTextual()) throw new IllegalArgumentException("Import values must be strings");
                if (value.textValue().length() > 16_000) {
                    throw new IllegalArgumentException("Imported resource values cannot exceed 16000 characters");
                }
                values.put(key, value.textValue());
            }
            return values;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Import JSON is invalid or contains duplicate keys", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Could not read import JSON", exception);
        }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not serialize import data", exception); }
    }

    private PreviewData readPreview(String value) {
        try { return json.readValue(value, PreviewData.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored import preview is invalid", exception); }
    }

    private ImportJobView view(Job job) {
        return new ImportJobView(Long.toString(job.id()), Long.toString(job.projectId()),
                job.locale(), job.status(), job.previewHash(), readPreview(job.previewJson()),
                job.error(), job.createdAt(), job.updatedAt());
    }

    private Job find(long projectId, long jobId) {
        return imports.find(projectId, jobId).orElseThrow(() -> new ImportJobNotFoundException(jobId));
    }

    private static int count(List<PreviewItem> changes, String action) {
        return (int) changes.stream().filter(change -> action.equals(change.action())).count();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CreatePreview(long actorId, long projectId, long namespaceId,
                                String locale, String idempotencyKey, String json) {
        public CreatePreview {
            if (actorId <= 0 || projectId <= 0 || namespaceId <= 0 || locale == null
                    || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,100}")) {
                throw new IllegalArgumentException("Actor, project, namespace, locale and valid idempotency key are required");
            }
        }
    }

    public record ApplyImport(long actorId, long projectId, long jobId, String previewHash) {
        public ApplyImport {
            if (actorId <= 0 || projectId <= 0 || jobId <= 0 || previewHash == null
                    || !previewHash.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Actor, project, job and SHA-256 preview hash are required");
            }
        }
    }

    public record PreviewData(String locale, String namespaceId, long projectVersion,
                              List<PreviewItem> items, int added, int updated,
                              int unchanged, int conflicts) { }

    public record PreviewItem(String key, String action, String entryId, String currentValue,
                              String incomingValue, long expectedSourceRevision,
                              long expectedTranslationVersion, String message) { }

    public record ImportJobView(String id, String projectId, String locale, String status,
                                String previewHash, PreviewData preview, String error,
                                java.time.Instant createdAt, java.time.Instant updatedAt) { }

    public record PreviewBody(long namespaceId, String locale, String idempotencyKey, String json) { }

    public record ApplyBody(String previewHash) { }

    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) { super("Idempotency key was already used for different import data: " + key); }
    }
    public static final class ImportPreviewConflictException extends RuntimeException {
        public ImportPreviewConflictException(String message) { super(message); }
    }
    public static final class ImportJobNotFoundException extends RuntimeException {
        public ImportJobNotFoundException(long jobId) { super("Import job not found: " + jobId); }
    }
}
