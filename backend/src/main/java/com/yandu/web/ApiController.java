package com.yandu.web;

import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.identity.CurrentActor;
import com.yandu.audit.AuditLog;
import com.yandu.imports.ImportApplicationService;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.workspace.WorkspaceApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
public class ApiController {

    private final CurrentActor actors;
    private final WorkspaceApplicationService workspace;
    private final CatalogEntryApplicationService catalog;
    private final TranslationApplicationService translations;
    private final ReleaseApplicationService releases;
    private final ImportApplicationService imports;
    private final AuditLog audit;

    public ApiController(CurrentActor actors, WorkspaceApplicationService workspace,
                         CatalogEntryApplicationService catalog,
                         TranslationApplicationService translations,
                         ReleaseApplicationService releases,
                         ImportApplicationService imports,
                         AuditLog audit) {
        this.actors = actors;
        this.workspace = workspace;
        this.catalog = catalog;
        this.translations = translations;
        this.releases = releases;
        this.imports = imports;
        this.audit = audit;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceApplicationService.ProjectView createProject(
            @AuthenticationPrincipal Jwt principal, @Valid @RequestBody CreateProjectBody body) {
        return workspace.create(new WorkspaceApplicationService.CreateProject(
                actors.resolve(principal), body.name(), body.sourceLocale()));
    }

    @GetMapping("/me")
    public CurrentActorView currentActor(@AuthenticationPrincipal Jwt principal) {
        return new CurrentActorView(Long.toString(actors.resolve(principal)));
    }

    @GetMapping("/projects/{projectId}")
    public WorkspaceApplicationService.ProjectView getProject(
            @AuthenticationPrincipal Jwt principal, @PathVariable long projectId) {
        return workspace.getProject(actors.resolve(principal), projectId);
    }

    @GetMapping("/projects")
    public java.util.List<WorkspaceApplicationService.ProjectView> listProjects(
            @AuthenticationPrincipal Jwt principal) {
        return workspace.listProjects(actors.resolve(principal));
    }

    @PostMapping("/projects/{projectId}/languages")
    public WorkspaceApplicationService.ProjectView addTargetLocale(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody TargetLocaleBody body) {
        return workspace.addTargetLocale(new WorkspaceApplicationService.AddTargetLocale(
                actors.resolve(principal), projectId, body.locale()));
    }

    @GetMapping("/projects/{projectId}/members")
    public java.util.List<ProjectMemberView> members(@AuthenticationPrincipal Jwt principal,
                                                     @PathVariable long projectId) {
        return workspace.members(actors.resolve(principal), projectId).stream()
                .map(member -> new ProjectMemberView(Long.toString(member.userId()),
                        member.role().name(), member.createdAt()))
                .toList();
    }

    @PostMapping("/projects/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public void addMember(@AuthenticationPrincipal Jwt principal,
                          @PathVariable long projectId,
                          @Valid @RequestBody AddMemberBody body) {
        workspace.addMember(new WorkspaceApplicationService.AddMember(
                actors.resolve(principal), projectId, positiveId(body.userId(), "userId"), body.role()));
    }

    @PostMapping("/projects/{projectId}/catalog/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogEntryApplicationService.EntryView createEntry(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody CreateEntryBody body) {
        var actorId = actors.resolve(principal);
        var sourceLocale = workspace.getProject(actorId, projectId).sourceLocale();
        return catalog.create(new CatalogEntryApplicationService.CreateEntry(
                actorId, projectId, positiveId(body.namespaceId(), "namespaceId"),
                sourceLocale, body.key(), body.sourceText()));
    }

    @GetMapping("/projects/{projectId}/catalog/entries")
    public CatalogEntryApplicationService.EntryPage searchEntries(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam long namespaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return catalog.search(new CatalogEntryApplicationService.SearchEntries(
                actors.resolve(principal), projectId, namespaceId, keyword, page, pageSize));
    }

    @PutMapping("/projects/{projectId}/catalog/entries/{entryId}/source")
    public CatalogEntryApplicationService.EntryView changeSource(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @Valid @RequestBody ChangeSourceBody body) {
        return catalog.changeSource(new CatalogEntryApplicationService.ChangeSource(
                actors.resolve(principal), projectId, entryId,
                body.expectedSourceRevision(), body.sourceText()));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/history")
    public java.util.List<CatalogEntryApplicationService.RevisionView> history(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId) {
        return catalog.history(new CatalogEntryApplicationService.ReadHistory(
                actors.resolve(principal), projectId, entryId));
    }

    @GetMapping("/projects/{projectId}/glossary")
    public CatalogEntryApplicationService.TermPage glossary(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam String sourceLocale,
            @RequestParam String targetLocale,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return catalog.listTerms(new CatalogEntryApplicationService.ReadTerms(
                actors.resolve(principal), projectId, sourceLocale, targetLocale, page, pageSize));
    }

    @PostMapping("/projects/{projectId}/glossary")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogEntryApplicationService.TermView addGlossaryTerm(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody AddGlossaryTermBody body) {
        return catalog.addTerm(new CatalogEntryApplicationService.AddTerm(
                actors.resolve(principal), projectId, body.sourceLocale(), body.targetLocale(),
                body.sourceTerm(), body.targetTerm(), body.note()));
    }

    @DeleteMapping("/projects/{projectId}/glossary/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveGlossaryTerm(@AuthenticationPrincipal Jwt principal,
                                    @PathVariable long projectId,
                                    @PathVariable long termId) {
        catalog.archiveTerm(new CatalogEntryApplicationService.ArchiveTerm(
                actors.resolve(principal), projectId, termId));
    }

    @DeleteMapping("/projects/{projectId}/catalog/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveEntry(@AuthenticationPrincipal Jwt principal,
                             @PathVariable long projectId,
                             @PathVariable long entryId) {
        catalog.archive(new CatalogEntryApplicationService.ArchiveEntry(
                actors.resolve(principal), projectId, entryId));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}")
    public TranslationApplicationService.TranslationView translation(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.get(new TranslationApplicationService.ReadTranslation(
                actors.resolve(principal), projectId, entryId, locale));
    }

    @GetMapping("/projects/{projectId}/translations")
    public TranslationApplicationService.WorkspacePage translationWorkspace(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam long namespaceId,
            @RequestParam String locale,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return translations.searchWorkspace(new TranslationApplicationService.SearchWorkspace(
                actors.resolve(principal), projectId, namespaceId, locale, status,
                keyword, page, pageSize));
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/quality")
    public java.util.List<TranslationApplicationService.QualityIssue> translationQuality(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.quality(actors.resolve(principal), projectId, entryId, locale);
    }

    @GetMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/suggestions")
    public TranslationApplicationService.Suggestions translationSuggestions(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale) {
        return translations.suggestions(actors.resolve(principal), projectId, entryId, locale);
    }

    @PutMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}")
    public TranslationApplicationService.TranslationView saveTranslation(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody SaveTranslationBody body) {
        return translations.save(new TranslationApplicationService.SaveTranslation(
                actors.resolve(principal), projectId, entryId, locale,
                body.expectedVersion(), body.basedOnSourceRevision(), body.value()));
    }

    @PostMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/submit")
    public TranslationApplicationService.TranslationView submitTranslation(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody SubmitTranslationBody body) {
        return translations.submit(new TranslationApplicationService.SubmitTranslation(
                actors.resolve(principal), projectId, entryId, locale, body.expectedVersion()));
    }

    @PostMapping("/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/review")
    public TranslationApplicationService.TranslationView reviewTranslation(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long entryId,
            @PathVariable String locale,
            @Valid @RequestBody ReviewTranslationBody body) {
        return translations.decide(new TranslationApplicationService.ReviewTranslation(
                actors.resolve(principal), projectId, entryId, locale,
                body.expectedVersion(), body.decision(), body.reason()));
    }

    @PostMapping("/projects/{projectId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseApplicationService.ReleaseView publishRelease(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @Valid @RequestBody PublishReleaseBody body) {
        return releases.publish(new ReleaseApplicationService.PublishRelease(
                actors.resolve(principal), projectId, body.locale(), body.format()));
    }

    @GetMapping("/projects/{projectId}/releases")
    public java.util.List<ReleaseApplicationService.ReleaseView> releaseHistory(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String format) {
        return releases.history(actors.resolve(principal), projectId, locale, format);
    }

    @GetMapping("/projects/{projectId}/releases/{releaseId}")
    public ReleaseApplicationService.ReleaseView release(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long releaseId) {
        return releases.get(actors.resolve(principal), projectId, releaseId);
    }

    @GetMapping("/projects/{projectId}/releases/{releaseId}/content")
    public ResponseEntity<String> releaseContent(
            @AuthenticationPrincipal Jwt principal,
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
    public ReleaseApplicationService.ReleaseView rollbackRelease(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long releaseId) {
        return releases.rollback(actors.resolve(principal), projectId, releaseId);
    }

    @PostMapping("/projects/{projectId}/imports/previews")
    public ImportApplicationService.ImportJobView createImportPreview(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ImportApplicationService.PreviewBody body) {
        return imports.preview(new ImportApplicationService.CreatePreview(
                actors.resolve(principal), projectId, body.namespaceId(), body.locale(),
                idempotencyKey, body.json()));
    }

    @GetMapping("/projects/{projectId}/imports/{jobId}")
    public ImportApplicationService.ImportJobView importJob(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId) {
        return imports.get(actors.resolve(principal), projectId, jobId);
    }

    @PostMapping("/projects/{projectId}/imports/{jobId}/apply")
    public ResponseEntity<ImportApplicationService.ImportJobView> applyImport(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        var view = imports.apply(new ImportApplicationService.ApplyImport(
                actors.resolve(principal), projectId, jobId, body.previewHash()));
        return ResponseEntity.accepted().body(view);
    }

    @PostMapping("/projects/{projectId}/imports/{jobId}/retry")
    public ResponseEntity<ImportApplicationService.ImportJobView> retryImport(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @PathVariable long jobId,
            @Valid @RequestBody ImportApplicationService.ApplyBody body) {
        var view = imports.retry(new ImportApplicationService.ApplyImport(
                actors.resolve(principal), projectId, jobId, body.previewHash()));
        return ResponseEntity.accepted().body(view);
    }

    @GetMapping("/projects/{projectId}/audit-log")
    public AuditLog.AuditPage auditHistory(
            @AuthenticationPrincipal Jwt principal,
            @PathVariable long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
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

    public record CreateProjectBody(@NotBlank @Size(max = 120) String name,
                                    @NotBlank @Size(max = 35) String sourceLocale) { }

    public record TargetLocaleBody(@NotBlank @Size(max = 35) String locale) { }

    public record CreateEntryBody(@NotBlank String namespaceId,
                                  @NotBlank @Size(max = 200) String key,
                                  @NotBlank @Size(max = 16000) String sourceText) { }

    public record AddGlossaryTermBody(@NotBlank @Size(max = 35) String sourceLocale,
                                      @NotBlank @Size(max = 35) String targetLocale,
                                      @NotBlank @Size(max = 200) String sourceTerm,
                                      @NotBlank @Size(max = 500) String targetTerm,
                                      @Size(max = 1000) String note) { }

    public record ChangeSourceBody(long expectedSourceRevision,
                                   @NotBlank @Size(max = 16000) String sourceText) { }

    public record SaveTranslationBody(long expectedVersion, long basedOnSourceRevision,
                                      @jakarta.validation.constraints.NotNull @Size(max = 16000) String value) { }

    public record SubmitTranslationBody(long expectedVersion) { }

    public record ReviewTranslationBody(long expectedVersion,
                                        @jakarta.validation.constraints.NotNull TranslationApplicationService.ReviewDecision decision,
                                        @Size(max = 2000) String reason) { }

    public record PublishReleaseBody(@NotBlank @Size(max = 35) String locale,
                                     @jakarta.validation.constraints.Pattern(
                                             regexp = "json-flat-v1|java-properties-v1") String format) { }

    public record PreviewBody(long namespaceId, @NotBlank @Size(max = 35) String locale,
                              @jakarta.validation.constraints.NotNull String json) { }

    public record ApplyBody(@jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{64}")
                            String previewHash) { }

    public record AddMemberBody(@NotBlank String userId,
                                @jakarta.validation.constraints.NotNull WorkspaceApplicationService.MemberRole role) { }

    public record CurrentActorView(String id) { }

    public record ProjectMemberView(String userId, String role, java.time.Instant createdAt) { }
}
