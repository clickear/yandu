package com.yandu.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ApiController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class})
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
}
