package com.yandu.imports;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.imports.application.ImportRepository.ProjectMigration;
import com.yandu.imports.application.ImportRepository.ProjectMigrations;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns the persisted preview, idempotency and resumable worker for local project migration. */
@Service
public class ProjectMigrationApplicationService {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;
    private static final int CHUNK_SIZE = 100;
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    private final ProjectMigrations migrations;
    private final WorkspaceApplicationService workspace;
    private final CatalogEntryApplicationService catalog;
    private final TranslationApplicationService translations;
    private final LongSupplier ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final TransactionTemplate transactions;

    public ProjectMigrationApplicationService(ProjectMigrations migrations,
            WorkspaceApplicationService workspace, CatalogEntryApplicationService catalog,
            TranslationApplicationService translations, LongSupplier ids, Clock clock,
            ObjectMapper json, TaskExecutor executor, PlatformTransactionManager transactionManager) {
        this.migrations = Objects.requireNonNull(migrations);
        this.workspace = Objects.requireNonNull(workspace);
        this.catalog = Objects.requireNonNull(catalog);
        this.translations = Objects.requireNonNull(translations);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.executor = Objects.requireNonNull(executor);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public MigrationView preview(long actorId, String packageJson, String targetName) {
        var parsed = parse(packageJson);
        var name = requiredName(targetName == null || targetName.isBlank()
                ? parsed.projectName() : targetName);
        var packageHash = sha256(parsed.canonicalPackage());
        var existing = migrations.findBySourceProject(actorId, parsed.sourceProjectId());
        var now = clock.instant();
        if (existing.isPresent()) {
            var old = existing.get();
            if (!old.packageHash().equals(packageHash)) {
                throw new MigrationConflictException("该本地项目已用不同内容发起迁移");
            }
            if ("COMPLETED".equals(old.status())) return view(old);
            if (old.targetProjectId() != null && !old.targetName().equals(name)) {
                throw new MigrationConflictException("迁移开始后目标名称不能再修改");
            }
            var changed = copy(old, name, previewJson(name, parsed.entries().size(),
                    workspace.projectNameExists(actorId, name)), previewHash(name, packageHash),
                    old.status(), old.targetProjectId(), old.completedEntries(), old.entryIdMapJson(),
                    old.version() + 1, now, old.error());
            if (!migrations.save(changed, old.version())) throw new MigrationConflictException("迁移任务已被更新，请重新加载");
            return view(changed);
        }
        var collision = workspace.projectNameExists(actorId, name);
        var preview = previewJson(name, parsed.entries().size(), collision);
        var migration = new ProjectMigration(ids.getAsLong(), actorId, parsed.sourceProjectId(),
                parsed.canonicalPackage(), packageHash, name, preview, previewHash(name, packageHash),
                "PREVIEW_READY", null, 0, "{}", 1, now, now, null);
        try {
            migrations.insert(migration);
        } catch (DataIntegrityViolationException duplicateSubmission) {
            var raced = migrations.findBySourceProject(actorId, parsed.sourceProjectId()).orElse(null);
            if (raced == null) throw duplicateSubmission;
            if (!raced.packageHash().equals(packageHash)) {
                throw new MigrationConflictException("该本地项目已用不同内容发起迁移");
            }
            return view(raced);
        }
        return view(migration);
    }

    @Transactional
    public MigrationView apply(long actorId, long migrationId, String previewHash) {
        var old = migrations.findForUpdate(migrationId)
                .orElseThrow(() -> new MigrationNotFoundException(migrationId));
        if (old.actorId() != actorId) throw new SecurityException("Migration task is not accessible");
        if (!Objects.equals(old.previewHash(), previewHash)) throw new MigrationConflictException("预览已过期，请重新预览");
        if ("COMPLETED".equals(old.status()) || "QUEUED".equals(old.status()) || "RUNNING".equals(old.status())) return view(old);
        if (old.targetProjectId() == null && workspace.projectNameExists(actorId, old.targetName())) {
            throw new MigrationConflictException("项目名称已存在，请修改目标名称后重新预览");
        }
        var project = readPackage(old.packageJson());
        var projectId = old.targetProjectId() == null
                ? workspace.createMigratingProject(actorId, old.targetName(), project.description(),
                        project.sourceLocale(), project.targetLocales())
                : old.targetProjectId();
        var queued = copy(old, old.targetName(), old.previewJson(), old.previewHash(), "QUEUED",
                projectId, 0, "{}", old.version() + 1, clock.instant(), null);
        if (!migrations.save(queued, old.version())) throw new MigrationConflictException("迁移任务已被更新，请重新加载");
        schedule();
        return view(queued);
    }

    public MigrationView get(long actorId, long migrationId) { return view(owned(actorId, migrationId)); }

    private ProjectMigration owned(long actorId, long id) {
        var migration = migrations.find(id).orElseThrow(() -> new MigrationNotFoundException(id));
        if (migration.actorId() != actorId) throw new SecurityException("Migration task is not accessible");
        return migration;
    }

    @Scheduled(fixedDelayString = "${yandu.import.poll-ms:2000}")
    public void poll() {
        migrations.recoverStaleMigrations(clock.instant().minus(Duration.ofMinutes(5)), clock.instant());
        for (var migration : migrations.findQueuedMigrations(10)) {
            executor.execute(() -> {
                if (migrations.claimMigration(migration.id(), clock.instant())) processChunk(migration.id());
            });
        }
    }

    public void schedule() { executor.execute(this::poll); }

    private void processChunk(long migrationId) {
        try {
            transactions.executeWithoutResult(status -> {
                var old = migrations.find(migrationId).orElse(null);
                if (old == null || !("QUEUED".equals(old.status()) || "RUNNING".equals(old.status()))) return;
                var project = readPackage(old.packageJson());
                var entries = project.entries();
                var end = Math.min(entries.size(), old.completedEntries() + CHUNK_SIZE);
                var mapping = readMap(old.entryIdMapJson());
                for (int i = old.completedEntries(); i < end; i++) {
                    var source = entries.get(i);
                    long entryId = catalog.createMigratedEntry(old.actorId(), old.targetProjectId(),
                            old.targetProjectId(), project.sourceLocale(), source.key(), source.source());
                    mapping.put(source.localId(), Long.toString(entryId));
                    for (var target : source.translations().entrySet()) {
                        if (!project.targetLocales().contains(target.getKey())) continue;
                        var value = target.getValue();
                        if (value == null || value.isBlank()) continue;
                        translations.createMigratedDraft(old.actorId(), old.targetProjectId(), entryId,
                                target.getKey(), value);
                    }
                }
                boolean done = end == entries.size();
                var updated = copy(old, old.targetName(), old.previewJson(), old.previewHash(),
                        done ? "COMPLETED" : "QUEUED", old.targetProjectId(), end,
                        writeMap(mapping), old.version() + 1, clock.instant(), null);
                if (!migrations.save(updated, old.version())) throw new MigrationConflictException("迁移进度写入冲突");
                if (done) workspace.activateMigratingProject(old.targetProjectId());
            });
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> {
                var old = migrations.find(migrationId).orElse(null);
                if (old == null || "COMPLETED".equals(old.status())) return;
                var failed = copy(old, old.targetName(), old.previewJson(), old.previewHash(), "FAILED",
                        old.targetProjectId(), old.completedEntries(), old.entryIdMapJson(),
                        old.version() + 1, clock.instant(), safeError(failure));
                migrations.save(failed, old.version());
            });
        }
    }

    private ParsedPackage parse(String input) {
        if (input == null || input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("迁移包不能超过 5 MiB");
        try {
            var root = STRICT_JSON.readTree(input);
            if (!"yandu-project-migration".equals(text(root, "format")) || integer(root, "schemaVersion") != 1) throw new IllegalArgumentException("不支持的迁移包格式或版本");
            var sourceId = text(root, "sourceProjectId");
            if (sourceId.isBlank() || sourceId.length() > 128) throw new IllegalArgumentException("本地项目标识无效");
            var project = root.path("project");
            var name = requiredName(text(project, "name"));
            var description = project.path("description").isTextual() ? project.path("description").asText() : "";
            var sourceLocale = WorkspaceApplicationService.normalizeLocale(text(project, "sourceLanguage"));
            var localeNode = project.path("targetLanguages");
            if (!localeNode.isArray() || localeNode.isEmpty()) throw new IllegalArgumentException("至少需要一个目标语言");
            var locales = new java.util.TreeSet<String>();
            for (var node : localeNode) {
                if (!node.isTextual()) throw new IllegalArgumentException("目标语言格式无效");
                locales.add(WorkspaceApplicationService.normalizeLocale(node.asText()));
            }
            if (locales.size() != localeNode.size()) throw new IllegalArgumentException("目标语言重复");
            if (locales.contains(sourceLocale)) throw new IllegalArgumentException("源语言不能同时作为目标语言");
            var items = root.path("entries");
            if (!items.isArray() || items.size() > MAX_ENTRIES) throw new IllegalArgumentException("词条数量不能超过 10,000");
            var seenKeys = new java.util.HashSet<String>();
            var seenIds = new java.util.HashSet<String>();
            var normalizedEntries = new ArrayList<EntryData>();
            var normalizedRoot = STRICT_JSON.createObjectNode();
            normalizedRoot.put("format", "yandu-project-migration"); normalizedRoot.put("schemaVersion", 1);
            normalizedRoot.put("sourceProjectId", sourceId);
            var normalizedProject = normalizedRoot.putObject("project");
            normalizedProject.put("name", name); normalizedProject.put("description", description);
            normalizedProject.put("sourceLanguage", sourceLocale);
            var normalizedLocales = normalizedProject.putArray("targetLanguages"); locales.forEach(normalizedLocales::add);
            var normalizedItems = normalizedRoot.putArray("entries");
            for (var item : items) {
                var localId = text(item, "id"); var key = text(item, "key").trim(); var source = text(item, "source");
                if (localId.isBlank() || localId.length() > 128 || !seenIds.add(localId)) throw new IllegalArgumentException("本地词条标识为空或重复");
                if (key.isBlank() || key.length() > 200 || !seenKeys.add(key)) throw new IllegalArgumentException("资源键为空、过长或重复: " + key);
                if (source.isBlank() || source.length() > 16000) throw new IllegalArgumentException("源文案为空或过长: " + key);
                var translationsNode = item.path("translations");
                if (!translationsNode.isObject()) throw new IllegalArgumentException("译文数据格式无效: " + key);
                var values = new TreeMap<String, String>();
                var normalizedItem = normalizedItems.addObject();
                normalizedItem.put("id", localId); normalizedItem.put("key", key); normalizedItem.put("source", source);
                var normalizedTranslations = normalizedItem.putObject("translations");
                var names = new java.util.ArrayList<String>(); translationsNode.fieldNames().forEachRemaining(names::add); names.sort(String::compareTo);
                for (var locale : names) {
                    var normalizedLocale = WorkspaceApplicationService.normalizeLocale(locale);
                    if (!locales.contains(normalizedLocale)) throw new IllegalArgumentException("译文使用了项目未配置的目标语言: " + locale);
                    var valueNode = translationsNode.path(locale).isObject() ? translationsNode.path(locale).path("value") : translationsNode.path(locale);
                    if (!valueNode.isTextual()) throw new IllegalArgumentException("译文格式无效: " + key + " / " + locale);
                    var value = valueNode.asText();
                    if (value.length() > 16000) throw new IllegalArgumentException("译文过长: " + key + " / " + locale);
                    values.put(normalizedLocale, value);
                    normalizedTranslations.put(normalizedLocale, value);
                }
                normalizedEntries.add(new EntryData(localId, key, source, values));
            }
            normalizedEntries.sort(java.util.Comparator.comparing(EntryData::localId));
            // The canonical package is rebuilt in stable key order; client workflow status is intentionally discarded.
            normalizedItems.removeAll();
            for (var entry : normalizedEntries) {
                var item = normalizedItems.addObject(); item.put("id", entry.localId()); item.put("key", entry.key()); item.put("source", entry.source());
                var values = item.putObject("translations"); entry.translations().forEach(values::put);
            }
            return new ParsedPackage(sourceId, name, description, sourceLocale, Set.copyOf(locales),
                    List.copyOf(normalizedEntries), json.writeValueAsString(normalizedRoot));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("迁移包不是有效 JSON 或包含重复字段", e);
        }
    }

    private ParsedPackage readPackage(String value) {
        // Persisted packages were strictly validated at preview time; parsing again keeps worker state self-contained.
        return parse(value);
    }

    private String previewJson(String name, int count, boolean collision) {
        try { return json.writeValueAsString(Map.of("targetName", name, "entryCount", count, "nameCollision", collision)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String previewHash(String name, String packageHash) { return sha256(name + "\n" + packageHash); }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String requiredName(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 120) throw new IllegalArgumentException("项目名称需为 1–120 个字符");
        return value.trim();
    }
    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isTextual()) throw new IllegalArgumentException("缺少文本字段: " + field);
        return value.asText();
    }
    private static int integer(JsonNode node, String field) {
        if (!node.path(field).canConvertToInt()) throw new IllegalArgumentException("迁移包版本无效");
        return node.path(field).asInt();
    }
    private static Map<String, String> readMap(String value) {
        try { return new LinkedHashMap<>(STRICT_JSON.readValue(value, STRICT_JSON.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class))); }
        catch (Exception e) { throw new IllegalStateException("迁移 ID 映射无法恢复", e); }
    }
    private String writeMap(Map<String, String> value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String safeError(RuntimeException failure) {
        var message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return message.substring(0, Math.min(500, message.length()));
    }
    private static ProjectMigration copy(ProjectMigration old, String name, String preview, String hash,
            String status, Long projectId, int completed, String map, long version,
            java.time.Instant updatedAt, String error) {
        return new ProjectMigration(old.id(), old.actorId(), old.sourceProjectId(), old.packageJson(),
                old.packageHash(), name, preview, hash, status, projectId, completed, map,
                version, old.createdAt(), updatedAt, error);
    }
    private MigrationView view(ProjectMigration value) {
        try { return new MigrationView(Long.toString(value.id()), value.status(), value.targetName(),
                value.previewHash(), json.readTree(value.previewJson()), value.targetProjectId() == null
                ? null : Long.toString(value.targetProjectId()), value.completedEntries(),
                readMap(value.entryIdMapJson()), value.error()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private record EntryData(String localId, String key, String source, Map<String, String> translations) { }
    private record ParsedPackage(String sourceProjectId, String projectName, String description,
            String sourceLocale, Set<String> targetLocales, List<EntryData> entries, String canonicalPackage) { }
    public record MigrationView(String id, String status, String targetName, String previewHash,
            JsonNode preview, String projectId, int completedEntries, Map<String, String> entryIdMap,
            String error) { }
    public static final class MigrationConflictException extends RuntimeException { public MigrationConflictException(String message) { super(message); } }
    public static final class MigrationNotFoundException extends RuntimeException { public MigrationNotFoundException(long id) { super("Migration task not found: " + id); } }
}
