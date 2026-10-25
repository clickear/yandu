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
import com.yandu.release.ReleaseApplicationService;
import com.yandu.imports.ImportApplicationService;
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
    private ReleaseApplicationService releases;

    @MockitoBean
    private ImportApplicationService imports;

    @MockitoBean
    private AuditLog audit;

    @Test
    void projectApiRejectsRequestsWithoutBearerIdentity() throws Exception {
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
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
}
