package com.yandu.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yandu.identity.CurrentActor;
import com.yandu.workspace.WorkspaceApplicationService;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.translation.MachineTranslationApplicationService;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.imports.ImportApplicationService;
import com.yandu.imports.ProjectMigrationApplicationService;
import com.yandu.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ApiController.class)
@EnableConfigurationProperties(org.springdoc.core.properties.SpringDocConfigProperties.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, OpenApiConfiguration.class,
        org.springdoc.core.configuration.SpringDocConfiguration.class,
        org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.audiences=yandu-api",
        "yandu.cors.allowed-origins=http://localhost:5173"
})
class ApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CurrentActor actors;

    @MockitoBean
    private WorkspaceApplicationService workspace;

    @MockitoBean
    private CatalogEntryApplicationService catalog;

    @MockitoBean
    private TranslationApplicationService translations;

    @MockitoBean
    private MachineTranslationApplicationService machineTranslations;

    @MockitoBean
    private ReleaseApplicationService releases;

    @MockitoBean
    private ImportApplicationService imports;

    @MockitoBean
    private ProjectMigrationApplicationService migrations;

    @MockitoBean
    private AuditLog audit;

    @Test
    void projectApiRejectsRequestsWithoutBearerIdentity() throws Exception {
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishedTranslationConsumerApiIsPublicAndReturnsVerifiedSnapshot() throws Exception {
        given(releases.latestPublished(1001L, "fr-FR", "json-flat-v1")).willReturn(
                new ReleaseApplicationService.PublicReleaseMetadata("42", "1001", "fr-FR",
                        "json-flat-v1", 7, "abc123", java.time.Instant.parse("2026-10-31T00:00:00Z")));
        given(releases.downloadPublished(1001L, "fr-FR", "json-flat-v1", 42L))
                .willReturn("{\"welcome\":\"Bonjour\"}");

        mvc.perform(get("/api/v1/public/projects/1001/translations/fr-FR/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.checksumSha256").value("abc123"));
        mvc.perform(get("/api/v1/public/projects/1001/translations/fr-FR/latest/content"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"welcome\":\"Bonjour\"}"));
    }

    @Test
    void websocketHandshakePathDoesNotRequireBearerAuthentication() throws Exception {
        mvc.perform(get("/api/v1/public/translations/ws"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthProbePathDoesNotRequireBearerIdentity() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectCreationUsesTheAuthenticatedActorAndReturnsStringIds() throws Exception {
        given(actors.resolve(any())).willReturn(101L);
        given(workspace.create(any())).willReturn(new WorkspaceApplicationService.ProjectView(
                "1001", "101", "Storefront", "zh-CN", java.util.Set.of(), 1, "1001"));

        mvc.perform(post("/api/v1/projects")
                        .with(jwt().jwt(token -> token.subject("external-subject")))
                        .contentType("application/json")
                        .content("""
                                {"name":"Storefront","sourceLocale":"zh-CN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("1001"))
                .andExpect(jsonPath("$.ownerId").value("101"));

        verify(workspace).create(new WorkspaceApplicationService.CreateProject(
                101L, "Storefront", "zh-CN"));
    }

    @Test
    void catalogEntryReadReturnsTheVersionRequiredForReorganization() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        given(catalog.get(41L, 10L, 20L)).willReturn(new CatalogEntryApplicationService.EntryView(
                "20", "10", "30", "home.title", "Welcome", 2, 7, false));

        mvc.perform(get("/api/v1/projects/10/catalog/entries/20")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("20"))
                .andExpect(jsonPath("$.namespaceId").value("30"))
                .andExpect(jsonPath("$.version").value(7));

        verify(catalog).get(41L, 10L, 20L);
    }

    @Test
    void archivedCatalogEntriesCanBeListedAndRestoredThroughApi() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        given(catalog.searchArchived(any())).willReturn(new CatalogEntryApplicationService.EntryPage(
                1, 50, 1, java.util.List.of(new CatalogEntryApplicationService.EntryView(
                        "20", "10", "30", "home.title", "Welcome", 2, 7, false))));

        mvc.perform(get("/api/v1/projects/10/catalog/archived-entries")
                        .queryParam("namespaceId", "30")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("home.title"));
        verify(catalog).searchArchived(new CatalogEntryApplicationService.SearchEntries(
                41L, 10L, 30L, null, 1, 50));

        mvc.perform(post("/api/v1/projects/10/catalog/entries/20/restore")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isNoContent());
        verify(catalog).restore(new CatalogEntryApplicationService.RestoreEntry(41L, 10L, 20L));
    }

    @Test
    void importHistoryIsScopedToTheProjectAndFailedJobsCanBeRetried() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        given(imports.listRecent(41L, 10L)).willReturn(java.util.List.of(
                new ImportApplicationService.ImportJobSummary("90", "en-US", "FAILED",
                        "a".repeat(64), 2, 1, 0, 0, "导入执行失败",
                        java.time.Instant.parse("2026-10-01T12:00:00Z"),
                        java.time.Instant.parse("2026-10-01T12:01:00Z"))));

        mvc.perform(get("/api/v1/projects/10/imports")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].updated").value(1));
        verify(imports).listRecent(41L, 10L);

        var now = java.time.Instant.parse("2026-10-01T12:01:00Z");
        given(imports.retry(any())).willReturn(new ImportApplicationService.ImportJobView(
                "90", "10", "en-US", "QUEUED", "a".repeat(64),
                new ImportApplicationService.PreviewData("en-US", "30", 1L,
                        java.util.List.of(), 0, 0, 0, 0), null, now, now));
        mvc.perform(post("/api/v1/projects/10/imports/90/retry")
                        .with(jwt().jwt(token -> token.subject("external-subject")))
                        .contentType("application/json")
                        .content("{\"previewHash\":\"" + "a".repeat(64) + "\"}"))
                .andExpect(status().isAccepted());
        verify(imports).retry(new ImportApplicationService.ApplyImport(
                41L, 10L, 90L, "a".repeat(64)));
    }

    @Test
    void translationReviewCanReturnWorkWithAnAuditableReason() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        given(translations.decide(any())).willReturn(new TranslationApplicationService.TranslationView(
                "30", "10", "20", "en-US", "Sign in", 3,
                com.yandu.translation.domain.Translation.Status.CHANGES_REQUESTED,
                3, 3, false, "41", "REQUEST_CHANGES", "Clarify terminology", "41", null));

        mvc.perform(post("/api/v1/projects/10/catalog/entries/20/translations/en-US/review")
                        .with(jwt().jwt(token -> token.subject("external-subject")))
                        .contentType("application/json")
                        .content("""
                                {"expectedVersion":2,"decision":"REQUEST_CHANGES","reason":"Clarify terminology"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHANGES_REQUESTED"));

        verify(translations).decide(new TranslationApplicationService.ReviewTranslation(
                41L, 10L, 20L, "en-US", 2,
                TranslationApplicationService.ReviewDecision.REQUEST_CHANGES, "Clarify terminology"));
    }

    @Test
    void translationReadReturnsTheLatestReviewFeedback() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        given(translations.get(any())).willReturn(new TranslationApplicationService.TranslationView(
                "30", "10", "20", "en-US", "Sign in", 3,
                com.yandu.translation.domain.Translation.Status.CHANGES_REQUESTED,
                3, 3, false, "41", "REQUEST_CHANGES", "Clarify terminology", "42",
                java.time.Instant.parse("2026-10-01T12:00:00Z")));

        mvc.perform(get("/api/v1/projects/10/catalog/entries/20/translations/en-US")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestReviewDecision").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.latestReviewReason").value("Clarify terminology"));
    }

    @Test
    void translationRevisionHistoryUsesTheAuthenticatedActorAndEntryLocaleScope() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        var createdAt = java.time.Instant.parse("2026-10-31T10:00:00Z");
        given(translations.history(any())).willReturn(java.util.List.of(
                new TranslationApplicationService.TranslationRevisionView(
                        "82", 2, "Sign in", 3, "42", createdAt)));

        mvc.perform(get("/api/v1/projects/10/catalog/entries/20/translations/en-US/history")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].value").value("Sign in"))
                .andExpect(jsonPath("$[0].editedBy").value("42"));
        verify(translations).history(new TranslationApplicationService.ReadTranslation(
                41L, 10L, 20L, "en-US"));
    }

    @Test
    void releaseRequestReturnsDurableJobForPolling() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        var createdAt = java.time.Instant.parse("2026-10-01T12:00:00Z");
        given(releases.requestPublish(any(), org.mockito.ArgumentMatchers.eq("release-key-1")))
                .willReturn(new ReleaseApplicationService.ReleaseJobView("70", "10", "en-US",
                        "json-flat-v1", "QUEUED", null, null, createdAt, createdAt));

        mvc.perform(post("/api/v1/projects/10/releases")
                        .with(jwt().jwt(token -> token.subject("external-subject")))
                        .header("Idempotency-Key", "release-key-1")
                        .contentType("application/json")
                        .content("{\"locale\":\"en-US\",\"format\":\"json-flat-v1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.id").value("70"));

        verify(releases).requestPublish(new ReleaseApplicationService.PublishRelease(
                41L, 10L, "en-US", "json-flat-v1"), "release-key-1");
    }

    @Test
    void releaseJobHistoryIsAvailableAfterTheClientRestarts() throws Exception {
        given(actors.resolve(any())).willReturn(41L);
        var createdAt = java.time.Instant.parse("2026-10-01T12:00:00Z");
        given(releases.listJobs(41L, 10L, "en-US")).willReturn(java.util.List.of(
                new ReleaseApplicationService.ReleaseJobView("70", "10", "en-US",
                        "json-flat-v1", "FAILED", null, "发布门禁失败", createdAt, createdAt)));

        mvc.perform(get("/api/v1/projects/10/releases/jobs")
                        .queryParam("locale", "en-US")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].error").value("发布门禁失败"));

        verify(releases).listJobs(41L, 10L, "en-US");
    }

    @Test
    void versionedOpenApiDocumentsTheMainWorkflowAndSecurityContract() throws Exception {
        mvc.perform(get("/api/v1/openapi")
                        .with(jwt().jwt(token -> token.subject("external-subject"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/translations'].get.parameters[0].name").value("projectId"))
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/translations'].get.parameters[0].schema.type").value("string"))
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/machine-translation-jobs'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/machine-translation-jobs'].post.parameters[?(@.name == 'Idempotency-Key')]").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/machine-translation-jobs'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/releases'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/public/projects/{projectId}/translations/{locale}/latest'].get.security").value(org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.paths['/api/v1/project-migrations/previews'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/catalog/entries/{entryId}/translations/{locale}/review'].post").exists());
    }

    @Test
    void unauthenticatedApiRequestsReturnProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Authentication required"))
                .andExpect(jsonPath("$.status").value(401));
    }
}
