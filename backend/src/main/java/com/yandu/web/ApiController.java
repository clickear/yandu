package com.yandu.web;

import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.identity.CurrentActor;
import com.yandu.audit.AuditLog;
import com.yandu.imports.ImportApplicationService;
import com.yandu.imports.ProjectMigrationApplicationService;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.translation.MachineTranslationApplicationService;
import com.yandu.workspace.WorkspaceApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Versioned JSON endpoints for project setup and source catalog workflows. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "YANDU", description = "项目、资源、翻译协作和交付工作流")
@SecurityRequirement(name = "bearerAuth")
public class ApiController {

    private final CurrentActor actors;
    private final WorkspaceApplicationService workspace;
    private final CatalogEntryApplicationService catalog;
    private final TranslationApplicationService translations;
    private final MachineTranslationApplicationService machineTranslations;
    private final ReleaseApplicationService releases;
    private final ImportApplicationService imports;
    private final ProjectMigrationApplicationService migrations;
    private final AuditLog audit;

    public ApiController(CurrentActor actors, WorkspaceApplicationService workspace,
                         CatalogEntryApplicationService catalog,
                         TranslationApplicationService translations,
                         MachineTranslationApplicationService machineTranslations,
                         ReleaseApplicationService releases,
                         ImportApplicationService imports,
                         ProjectMigrationApplicationService migrations,
                         AuditLog audit) {
        this.actors = actors;
        this.workspace = workspace;
        this.catalog = catalog;
        this.translations = translations;
        this.machineTranslations = machineTranslations;
        this.releases = releases;
        this.imports = imports;
        this.migrations = migrations;
        this.audit = audit;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建项目", description = "创建项目及其源语言、目标语言和默认命名空间。")
    public WorkspaceApplicationService.ProjectView createProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @Valid @RequestBody CreateProjectBody body) {
        return workspace.create(new WorkspaceApplicationService.CreateProject(
                actors.resolve(principal), body.name(), body.sourceLocale(), body.description(),
                body.targetLocales() == null ? null : java.util.Set.copyOf(body.targetLocales())));
    }

    @GetMapping("/me")
    public CurrentActorView currentActor(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal) {
        return new CurrentActorView(Long.toString(actors.resolve(principal)));
    }

    @GetMapping("/projects/{projectId}")
    public WorkspaceApplicationService.ProjectView getProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        return workspace.getProject(actors.resolve(principal), projectId);
    }

    @GetMapping("/projects")
    public java.util.List<WorkspaceApplicationService.ProjectView> listProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal) {
        return workspace.listProjects(actors.resolve(principal));
    }

    @PostMapping("/projects/{projectId}/languages")
    @Operation(summary = "添加目标语言")
    public WorkspaceApplicationService.ProjectView addTargetLocale(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody TargetLocaleBody body) {
        return workspace.addTargetLocale(new WorkspaceApplicationService.AddTargetLocale(
                actors.resolve(principal), projectId, body.locale()));
    }

    @GetMapping("/projects/{projectId}/namespaces")
    public java.util.List<WorkspaceApplicationService.NamespaceView> namespaces(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return workspace.namespaces(actors.resolve(principal), projectId, includeArchived);
    }

    @PostMapping("/projects/{projectId}/namespaces")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建命名空间")
    public WorkspaceApplicationService.NamespaceView createNamespace(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @Valid @RequestBody CreateNamespaceBody body) {
        return workspace.createNamespace(new WorkspaceApplicationService.CreateNamespace(
                actors.resolve(principal), projectId, body.name()));
    }

    @PutMapping("/projects/{projectId}/namespaces/{namespaceId}")
    public WorkspaceApplicationService.NamespaceView renameNamespace(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @PathVariable long namespaceId, @Valid @RequestBody CreateNamespaceBody body) {
        return workspace.renameNamespace(new WorkspaceApplicationService.RenameNamespace(
                actors.resolve(principal), projectId, namespaceId, body.name()));
    }

    @DeleteMapping("/projects/{projectId}/namespaces/{namespaceId}")
    public void archiveNamespace(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
                                 @PathVariable long namespaceId) {
        workspace.archiveNamespace(new WorkspaceApplicationService.NamespaceCommand(
                actors.resolve(principal), projectId, namespaceId));
    }

    @PostMapping("/projects/{projectId}/namespaces/{namespaceId}/restore")
    public WorkspaceApplicationService.NamespaceView restoreNamespace(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @PathVariable long namespaceId) {
        return workspace.restoreNamespace(new WorkspaceApplicationService.NamespaceCommand(
                actors.resolve(principal), projectId, namespaceId));
    }

    @GetMapping("/projects/{projectId}/members")
    public java.util.List<ProjectMemberView> members(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                                                     @PathVariable long projectId) {
        return workspace.members(actors.resolve(principal), projectId).stream()
                .map(member -> new ProjectMemberView(Long.toString(member.userId()),
                        member.role().name(), member.createdAt()))
                .toList();
    }

    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                          @PathVariable long projectId,
                          @Valid @RequestBody AddMemberBody body) {
        workspace.addMember(new WorkspaceApplicationService.AddMember(
                actors.resolve(principal), projectId, positiveId(body.userId(), "userId"), body.role()));
    }

    @PutMapping("/projects/{projectId}/members/{userId}")
    public void changeMemberRole(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
                                 @PathVariable long userId, @Valid @RequestBody ChangeMemberRoleBody body) {
        workspace.changeMemberRole(new WorkspaceApplicationService.ChangeMemberRole(
                actors.resolve(principal), projectId, userId, body.role()));
    }

    @DeleteMapping("/projects/{projectId}/members/{userId}")
    public void removeMember(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
                             @PathVariable long userId) {
        workspace.removeMember(new WorkspaceApplicationService.RemoveMember(
                actors.resolve(principal), projectId, userId));
    }

    @PostMapping("/projects/{projectId}/leave")
    public void leaveProject(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        workspace.leaveProject(actors.resolve(principal), projectId);
    }

    @PostMapping("/projects/{projectId}/ownership-transfer")
    public void transferProjectOwnership(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
                                         @Valid @RequestBody TransferOwnershipBody body) {
        workspace.transferOwnership(new WorkspaceApplicationService.TransferOwnership(
                actors.resolve(principal), projectId, positiveId(body.newOwnerId(), "newOwnerId")));
    }

    @PostMapping("/projects/{projectId}/machine-translation-jobs")
    @Operation(summary = "创建机器翻译任务", description = "对符合条件的未译文案排队；提供 Idempotency-Key 后重复请求返回同一任务。结果只保存为草稿。")
    public ResponseEntity<MachineTranslationApplicationService.JobView> createMachineTranslationJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateMachineTranslationJobBody body) {
        var job = machineTranslations.create(new MachineTranslationApplicationService.CreateJob(
                actors.resolve(principal), projectId, body.locale(), idempotencyKey,
                body.entryIds() == null ? null : body.entryIds().stream()
                        .map(id -> positiveId(id, "entryId")).toList()));
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/projects/{projectId}/machine-translation-jobs")
    public java.util.List<MachineTranslationApplicationService.JobView> machineTranslationJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        return machineTranslations.list(actors.resolve(principal), projectId);
    }

    @GetMapping("/projects/{projectId}/machine-translation-jobs/{jobId}")
    public MachineTranslationApplicationService.JobView machineTranslationJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId, @PathVariable long jobId) {
        return machineTranslations.get(actors.resolve(principal), projectId, jobId);
    }

    @GetMapping("/projects/{projectId}/machine-translation-jobs/{jobId}/items")
    @Operation(summary = "分页查看机器翻译词条结果")
    public MachineTranslationApplicationService.JobItemPage machineTranslationJobItems(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId, @PathVariable long jobId,
            @Parameter(description = "从 1 开始的页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大 200") @RequestParam(defaultValue = "50") int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("page must be positive and pageSize must be between 1 and 200");
        }
        return machineTranslations.items(actors.resolve(principal), projectId, jobId, page, pageSize);
    }

    @PostMapping("/projects/{projectId}/machine-translation-jobs/{jobId}/retry")
    @Operation(summary = "重试机器翻译失败词条")
    public MachineTranslationApplicationService.JobView retryMachineTranslationJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId, @PathVariable long jobId) {
        return machineTranslations.retry(new MachineTranslationApplicationService.RetryJob(
                actors.resolve(principal), projectId, jobId));
    }

    @PostMapping("/projects/{projectId}/archive")
    public void archiveProject(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        workspace.archiveProject(actors.resolve(principal), projectId);
    }

    @PostMapping("/projects/{projectId}/restore")
    public void restoreProject(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        workspace.restoreProject(actors.resolve(principal), projectId);
    }

    @DeleteMapping("/projects/{projectId}/languages/{locale}")
    public java.util.List<String> archiveTargetLocale(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                                                       @PathVariable long projectId,
                                                       @PathVariable String locale) {
        return workspace.archiveTargetLocale(actors.resolve(principal), projectId, locale);
    }

    @PostMapping("/projects/{projectId}/languages/{locale}/restore")
    public java.util.List<String> restoreTargetLocale(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                                                       @PathVariable long projectId,
                                                       @PathVariable String locale) {
        return workspace.restoreTargetLocale(actors.resolve(principal), projectId, locale);
    }

    @GetMapping("/projects/{projectId}/languages/archived")
    public java.util.List<String> archivedTargetLocales(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                                                        @PathVariable long projectId) {
        return workspace.archivedTargetLocales(actors.resolve(principal), projectId);
    }

    @PostMapping("/projects/{projectId}/catalog/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建源词条")
    public CatalogEntryApplicationService.EntryView createEntry(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody CreateEntryBody body) {
        var actorId = actors.resolve(principal);
        var sourceLocale = workspace.getProject(actorId, projectId).sourceLocale();
        return catalog.create(new CatalogEntryApplicationService.CreateEntry(
                actorId, projectId, positiveId(body.namespaceId(), "namespaceId"),
                sourceLocale, body.key(), body.sourceText()));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}")
    @Operation(summary = "读取词条目录信息", description = "返回当前词条键、命名空间和目录版本，供安全的改键或移动操作提交期望版本。")
    public CatalogEntryApplicationService.EntryView catalogEntry(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId) {
        return catalog.get(actors.resolve(principal), projectId, entryId);
    }

    @GetMapping("/projects/{projectId}/catalog/entries")
    @Operation(summary = "搜索源词条")
    public CatalogEntryApplicationService.EntryPage searchEntries(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam long namespaceId,
            @RequestParam(required = false) String keyword,
            @Parameter(description = "从 1 开始的页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(defaultValue = "50") int pageSize) {
        return catalog.search(new CatalogEntryApplicationService.SearchEntries(
                actors.resolve(principal), projectId, namespaceId, keyword, page, pageSize));
    }

    @GetMapping("/projects/{projectId}/catalog/archived-entries")
    @Operation(summary = "搜索已归档词条")
    public CatalogEntryApplicationService.EntryPage searchArchivedEntries(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId, @RequestParam long namespaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return catalog.searchArchived(new CatalogEntryApplicationService.SearchEntries(
                actors.resolve(principal), projectId, namespaceId, keyword, page, pageSize));
    }

    @PutMapping("/projects/{projectId}/catalog/entries/{entryId}/source")
    public CatalogEntryApplicationService.EntryView changeSource(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @Valid @RequestBody ChangeSourceBody body) {
        return catalog.changeSource(new CatalogEntryApplicationService.ChangeSource(
                actors.resolve(principal), projectId, entryId,
                body.expectedSourceRevision(), body.sourceText()));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/history")
    public java.util.List<CatalogEntryApplicationService.RevisionView> history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId) {
        return catalog.history(new CatalogEntryApplicationService.ReadHistory(
                actors.resolve(principal), projectId, entryId));
    }

    @GetMapping("/projects/{projectId}/glossary")
    public CatalogEntryApplicationService.TermPage glossary(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam String sourceLocale,
            @RequestParam String targetLocale,
            @Parameter(description = "从 1 开始的页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(defaultValue = "50") int pageSize) {
        return catalog.listTerms(new CatalogEntryApplicationService.ReadTerms(
                actors.resolve(principal), projectId, sourceLocale, targetLocale, page, pageSize));
    }

    @PostMapping("/projects/{projectId}/glossary")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogEntryApplicationService.TermView addGlossaryTerm(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody AddGlossaryTermBody body) {
        return catalog.addTerm(new CatalogEntryApplicationService.AddTerm(
                actors.resolve(principal), projectId, body.sourceLocale(), body.targetLocale(),
                body.sourceTerm(), body.targetTerm(), body.note()));
    }

    @DeleteMapping("/projects/{projectId}/glossary/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveGlossaryTerm(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                                    @PathVariable long projectId,
                                    @PathVariable long termId) {
        catalog.archiveTerm(new CatalogEntryApplicationService.ArchiveTerm(
                actors.resolve(principal), projectId, termId));
    }

    @DeleteMapping("/projects/{projectId}/catalog/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveEntry(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                             @PathVariable long projectId,
                             @PathVariable long entryId) {
        catalog.archive(new CatalogEntryApplicationService.ArchiveEntry(
                actors.resolve(principal), projectId, entryId));
    }

    @PostMapping("/projects/{projectId}/catalog/entries/{entryId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restoreEntry(@Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
                             @PathVariable long projectId, @PathVariable long entryId) {
        catalog.restore(new CatalogEntryApplicationService.RestoreEntry(
                actors.resolve(principal), projectId, entryId));
    }

    @PutMapping("/projects/{projectId}/catalog/entries/{entryId}/organization")
    public CatalogEntryApplicationService.EntryView reorganizeEntry(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @PathVariable long entryId, @Valid @RequestBody ReorganizeEntryBody body) {
        return catalog.reorganize(new CatalogEntryApplicationService.ReorganizeEntry(
                actors.resolve(principal), projectId, entryId, body.expectedVersion(),
                positiveId(body.namespaceId(), "namespaceId"), body.key()));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}")
    public TranslationApplicationService.TranslationView translation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.get(new TranslationApplicationService.ReadTranslation(
                actors.resolve(principal), projectId, entryId, locale));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/history")
    @Operation(summary = "读取译文修订历史", description = "按版本倒序返回最近 100 条不可变译文快照。")
    public java.util.List<TranslationApplicationService.TranslationRevisionView> translationHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId, @PathVariable long entryId, @PathVariable String locale) {
        return translations.history(new TranslationApplicationService.ReadTranslation(
                actors.resolve(principal), projectId, entryId, locale));
    }

    @GetMapping("/projects/{projectId}/translations")
    @Operation(summary = "分页查询翻译工作台")
    public TranslationApplicationService.WorkspacePage translationWorkspace(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam long namespaceId,
            @RequestParam String locale,
            @Parameter(description = "状态过滤：all、untranslated、draft、in-review、changes-requested、needs-review、reviewed")
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String keyword,
            @Parameter(description = "从 1 开始的页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(defaultValue = "50") int pageSize) {
        return translations.searchWorkspace(new TranslationApplicationService.SearchWorkspace(
                actors.resolve(principal), projectId, namespaceId, locale, status,
                keyword, page, pageSize));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/quality")
    public java.util.List<TranslationApplicationService.QualityIssue> translationQuality(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.quality(actors.resolve(principal), projectId, entryId, locale);
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/suggestions")
    public TranslationApplicationService.Suggestions translationSuggestions(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.suggestions(actors.resolve(principal), projectId, entryId, locale);
    }

    @PutMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}")
    @Operation(summary = "保存或修订译文", description = "必须提交 expectedVersion 和 basedOnSourceRevision；版本冲突返回 409。")
    public TranslationApplicationService.TranslationView saveTranslation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody SaveTranslationBody body) {
        return translations.save(new TranslationApplicationService.SaveTranslation(
                actors.resolve(principal), projectId, entryId, locale,
                body.expectedVersion(), body.basedOnSourceRevision(), body.value()));
    }

    @PostMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/submit")
    @Operation(summary = "提交译文审核")
    public TranslationApplicationService.TranslationView submitTranslation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody SubmitTranslationBody body) {
        return translations.submit(new TranslationApplicationService.SubmitTranslation(
                actors.resolve(principal), projectId, entryId, locale, body.expectedVersion()));
    }

    @PostMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/review")
    @Operation(summary = "通过或退回译文", description = "REQUEST_CHANGES 必须提供退回原因；决定绑定精确译文版本。")
    public TranslationApplicationService.TranslationView reviewTranslation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody ReviewTranslationBody body) {
        return translations.decide(new TranslationApplicationService.ReviewTranslation(
                actors.resolve(principal), projectId, entryId, locale,
                body.expectedVersion(), body.decision(), body.reason()));
    }

    @PostMapping("/projects/{projectId}/releases")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "创建异步发布任务", description = "按语言和格式执行发布门禁、构建不可变快照。任务返回 202，使用任务查询接口跟踪。")
    public ReleaseApplicationService.ReleaseJobView requestRelease(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PublishReleaseBody body) {
        return releases.requestPublish(new ReleaseApplicationService.PublishRelease(
                actors.resolve(principal), projectId, body.locale(), body.format()), idempotencyKey);
    }

    @GetMapping("/projects/{projectId}/releases/jobs/{jobId}")
    @Operation(summary = "查询发布任务")
    public ReleaseApplicationService.ReleaseJobView releaseJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @PathVariable long jobId) {
        return releases.getJob(actors.resolve(principal), projectId, jobId);
    }

    @GetMapping("/projects/{projectId}/releases/jobs")
    @Operation(summary = "查询最近发布任务")
    public java.util.List<ReleaseApplicationService.ReleaseJobView> releaseJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @RequestParam(required = false) String locale) {
        return releases.listJobs(actors.resolve(principal), projectId, locale);
    }

    @PostMapping("/projects/{projectId}/releases/jobs/{jobId}/retry")
    @Operation(summary = "重试失败的发布任务")
    public ReleaseApplicationService.ReleaseJobView retryReleaseJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long projectId,
            @PathVariable long jobId) {
        return releases.retryJob(actors.resolve(principal), projectId, jobId);
    }

    @GetMapping("/projects/{projectId}/releases")
    @Operation(summary = "查看发布版本历史")
    public java.util.List<ReleaseApplicationService.ReleaseSummary> releaseHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String format) {
        return releases.historySummaries(actors.resolve(principal), projectId, locale, format);
    }

    @GetMapping("/projects/{projectId}/releases/{releaseId}")
    public ReleaseApplicationService.ReleaseSummary release(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long releaseId) {
        return ReleaseApplicationService.summary(releases.get(actors.resolve(principal), projectId, releaseId));
    }

    @GetMapping("/public/projects/{projectId}/translations/{locale}/latest")
    @Operation(summary = "读取最新已发布翻译版本", description = "面向消费端的公开只读接口；仅返回已发布快照元数据。")
    @SecurityRequirements
    public ResponseEntity<ReleaseApplicationService.PublicReleaseMetadata> latestPublicRelease(
            @PathVariable long projectId, @PathVariable String locale,
            @RequestParam(defaultValue = ReleaseApplicationService.JSON_FLAT) String format,
            @RequestHeader(value = org.springframework.http.HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var metadata = releases.latestPublished(projectId, locale, format);
        var etag = "\"" + metadata.checksumSha256() + "\"";
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(metadata.checksumSha256()).build();
        return ResponseEntity.ok().eTag(metadata.checksumSha256())
                .cacheControl(org.springframework.http.CacheControl.noCache()).body(metadata);
    }

    @GetMapping("/public/projects/{projectId}/translations/{locale}/latest/content")
    @Operation(summary = "下载已发布翻译快照", description = "面向消费端的公开只读接口；正文校验 SHA-256 并按不可变版本下载。")
    @SecurityRequirements
    public ResponseEntity<String> publicReleaseContent(
            @PathVariable long projectId, @PathVariable String locale,
            @RequestParam(defaultValue = ReleaseApplicationService.JSON_FLAT) String format,
            @RequestHeader(value = org.springframework.http.HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var metadata = releases.latestPublished(projectId, locale, format);
        var content = releases.downloadPublished(projectId, locale, format, Long.parseLong(metadata.id()));
        var etag = "\"" + metadata.checksumSha256() + "\"";
        var mediaType = ReleaseApplicationService.JAVA_PROPERTIES.equals(format)
                ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON;
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(metadata.checksumSha256()).build();
        return ResponseEntity.ok().eTag(metadata.checksumSha256())
                .cacheControl(org.springframework.http.CacheControl.noCache()).contentType(mediaType).body(content);
    }

    @GetMapping("/projects/{projectId}/releases/{releaseId}/content")
    @Operation(summary = "下载已发布文件")
    public ResponseEntity<String> releaseContent(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long releaseId) {
        var actorId = actors.resolve(principal);
        var release = releases.get(actorId, projectId, releaseId);
        var content = releases.download(actorId, projectId, releaseId);
        var properties = ReleaseApplicationService.JAVA_PROPERTIES.equals(release.format());
        var mediaType = properties ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON;
        var extension = properties ? "properties" : "json";
        return ResponseEntity.ok().contentType(mediaType)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"yandu-" + release.locale() + "." + extension + "\"")
                .body(content);
    }

    @PostMapping("/projects/{projectId}/releases/{releaseId}/rollback")
    public ReleaseApplicationService.ReleaseSummary rollbackRelease(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long releaseId) {
        return ReleaseApplicationService.summary(releases.rollback(actors.resolve(principal), projectId, releaseId));
    }

    @PostMapping("/projects/{projectId}/imports/previews")
    @Operation(summary = "预览 JSON 导入", description = "请求需带 Idempotency-Key；解析并返回差异预览，不立即修改项目数据。")
    public ImportApplicationService.ImportJobView createImportPreview(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ImportApplicationService.PreviewBody body) {
        return imports.preview(new ImportApplicationService.CreatePreview(
                actors.resolve(principal), projectId, body.namespaceId(), body.locale(),
                idempotencyKey, body.json()));
    }

    @GetMapping("/projects/{projectId}/imports/{jobId}")
    public ImportApplicationService.ImportJobView importJob(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId) {
        return imports.get(actors.resolve(principal), projectId, jobId);
    }

    @GetMapping("/projects/{projectId}/imports")
    @Operation(summary = "查询最近的 JSON 导入任务")
    public java.util.List<ImportApplicationService.ImportJobSummary> importJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId) {
        return imports.listRecent(actors.resolve(principal), projectId);
    }

    @PostMapping("/projects/{projectId}/imports/{jobId}/apply")
    @Operation(summary = "确认并排队导入")
    public ResponseEntity<ImportApplicationService.ImportJobView> applyImport(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        var view = imports.apply(new ImportApplicationService.ApplyImport(
                actors.resolve(principal), projectId, jobId, body.previewHash()));
        return ResponseEntity.accepted().body(view);
    }

    @PostMapping("/projects/{projectId}/imports/{jobId}/retry")
    @Operation(summary = "重试失败的导入任务")
    public ResponseEntity<ImportApplicationService.ImportJobView> retryImport(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        var view = imports.retry(new ImportApplicationService.ApplyImport(
                actors.resolve(principal), projectId, jobId, body.previewHash()));
        return ResponseEntity.accepted().body(view);
    }

    @PostMapping("/project-migrations/previews")
    @Operation(summary = "预览本地项目迁移包", description = "校验 yandu-project-migration v1 JSON 包并返回冲突预览；此操作不创建项目数据。")
    public ProjectMigrationApplicationService.MigrationView previewProjectMigration(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody ProjectMigrationBody body) {
        return migrations.preview(actors.resolve(principal), body.packageJson(), body.targetName());
    }

    @GetMapping("/project-migrations/{migrationId}")
    @Operation(summary = "查询本地项目迁移任务")
    public ProjectMigrationApplicationService.MigrationView projectMigration(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long migrationId) {
        return migrations.get(actors.resolve(principal), migrationId);
    }

    @PostMapping("/project-migrations/{migrationId}/apply")
    @Operation(summary = "应用本地项目迁移包", description = "异步创建项目并迁入词条和草稿译文，返回 202。")
    public ResponseEntity<ProjectMigrationApplicationService.MigrationView> applyProjectMigration(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long migrationId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        return ResponseEntity.accepted().body(migrations.apply(
                actors.resolve(principal), migrationId, body.previewHash()));
    }

    @PostMapping("/project-migrations/{migrationId}/retry")
    @Operation(summary = "重试失败的本地项目迁移")
    public ResponseEntity<ProjectMigrationApplicationService.MigrationView> retryProjectMigration(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal, @PathVariable long migrationId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        return ResponseEntity.accepted().body(migrations.apply(
                actors.resolve(principal), migrationId, body.previewHash()));
    }

    @GetMapping("/projects/{projectId}/audit-log")
    public AuditLog.AuditPage auditHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Parameter(description = "从 1 开始的页码") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大 100") @RequestParam(defaultValue = "50") int pageSize) {
        workspace.getProject(actors.resolve(principal), projectId);
        return audit.page(projectId, page, pageSize);
    }

    private static long positiveId(String value, String field) {
        try {
            var id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Use one validation response for malformed and non-positive identifiers.
        }
        throw new IllegalArgumentException(field + " must be a positive decimal string");
    }

    public record CreateProjectBody(@Schema(description = "项目名称", example = "Storefront") @NotBlank @Size(max = 120) String name,
                                    @NotBlank @Size(max = 35) String sourceLocale,
                                    @Size(max = 2000) String description,
                                    @Schema(description = "可选目标语言；未传时由服务端配置默认语言")
                                    java.util.List<@NotBlank @Size(max = 35) String> targetLocales) { }

    public record TargetLocaleBody(@NotBlank @Size(max = 35) String locale) { }

    public record CreateNamespaceBody(@NotBlank @Size(max = 120) String name) { }

    public record CreateEntryBody(@Schema(description = "命名空间数字 ID，十进制字符串") @NotBlank String namespaceId,
                                  @NotBlank @Size(max = 200) String key,
                                  @Schema(description = "源文案，最多 16000 个字符")
                                  @NotBlank @Size(max = 16000) String sourceText) { }

    public record AddGlossaryTermBody(@NotBlank @Size(max = 35) String sourceLocale,
                                      @NotBlank @Size(max = 35) String targetLocale,
                                      @NotBlank @Size(max = 200) String sourceTerm,
                                      @NotBlank @Size(max = 500) String targetTerm,
                                      @Size(max = 1000) String note) { }

    public record ChangeSourceBody(long expectedSourceRevision,
                                   @NotBlank @Size(max = 16000) String sourceText) { }

    public record ReorganizeEntryBody(long expectedVersion, @NotBlank String namespaceId,
                                      @NotBlank @Size(max = 200) String key) { }

    public record SaveTranslationBody(@Schema(description = "客户端最后读取到的译文版本") long expectedVersion,
                                      @Schema(description = "译文依据的源文修订版本") long basedOnSourceRevision,
                                      @jakarta.validation.constraints.NotNull @Size(max = 16000) String value) { }

    public record SubmitTranslationBody(long expectedVersion) { }

    public record ReviewTranslationBody(@Schema(description = "要审核的译文版本") long expectedVersion,
                                        @jakarta.validation.constraints.NotNull TranslationApplicationService.ReviewDecision decision,
                                        @Schema(description = "退回修改时必填") @Size(max = 2000) String reason) { }

    public record PublishReleaseBody(@Schema(description = "目标语言标签", example = "en-US")
                                     @NotBlank @Size(max = 35) String locale,
                                     @jakarta.validation.constraints.Pattern(
                                             regexp = "json-flat-v1|java-properties-v1")
                                     @Schema(description = "导出格式", allowableValues = {"json-flat-v1", "java-properties-v1"})
                                     String format) { }

    public record PreviewBody(long namespaceId, @NotBlank @Size(max = 35) String locale,
                              @jakarta.validation.constraints.NotNull String json) { }

    public record ProjectMigrationBody(@Schema(description = "UTF-8 JSON 包，format=yandu-project-migration、schemaVersion=1；最多 5 MiB")
                                       @NotBlank @Size(max = 5_500_000) String packageJson,
                                       @Size(max = 120) String targetName) { }

    public record ApplyBody(@jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}")
                            String previewHash) { }

    public record AddMemberBody(@NotBlank String userId,
                                @jakarta.validation.constraints.NotNull WorkspaceApplicationService.MemberRole role) { }

    public record ChangeMemberRoleBody(@jakarta.validation.constraints.NotNull WorkspaceApplicationService.MemberRole role) { }
    public record TransferOwnershipBody(@NotBlank String newOwnerId) { }
    public record CreateMachineTranslationJobBody(@NotBlank String locale,
                                                   java.util.List<String> entryIds) { }

    public record CurrentActorView(String id) { }

    public record ProjectMemberView(String userId, String role, java.time.Instant createdAt) { }
}
