package com.yandu.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.audit.AuditLog;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.imports.application.ImportRepository;
import com.yandu.translation.TranslationApplicationService;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

class ImportTest {
    private ImportRepository imports;
    private CatalogEntryApplicationService catalog;
    private ImportApplicationService service;

    @BeforeEach
    void setUp() {
        imports = mock(ImportRepository.class);
        catalog = mock(CatalogEntryApplicationService.class);
        var translations = mock(TranslationApplicationService.class);
        var workspace = mock(WorkspaceApplicationService.class);
        var access = mock(ProjectAccessPolicy.class);
        given(workspace.getProject(41L, 10L)).willReturn(new WorkspaceApplicationService.ProjectView(
                "10", "41", "Store", "zh-CN", Set.of("en-US"), 7, "10"));
        given(imports.findByIdempotencyKey(10L, "request-0001")).willReturn(Optional.empty());
        given(catalog.search(any())).willReturn(new CatalogEntryApplicationService.EntryPage(1, 100, 1,
                List.of(new CatalogEntryApplicationService.EntryView(
                        "20", "10", "home.title", "旧文案", 3, 3, false))));
        TaskExecutor directExecutor = Runnable::run;
        service = new ImportApplicationService(imports, catalog, translations, workspace, access,
                () -> 500L, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                new ObjectMapper(), directExecutor, mock(PlatformTransactionManager.class),
                mock(AuditLog.class));
    }

    @Test
    void previewShowsChangesWithStableHashAndRejectsDuplicateJsonKeys() {
        var preview = service.preview(new ImportApplicationService.CreatePreview(41L, 10L, 10L,
                "zh-CN", "request-0001", "{\"home.title\":\"新文案\"}"));

        assertEquals("PREVIEW_READY", preview.status());
        assertEquals(1, preview.preview().updated());
        assertEquals("UPDATE", preview.preview().items().getFirst().action());
        assertEquals("旧文案", preview.preview().items().getFirst().currentValue());
        assertTrue(preview.previewHash().matches("[a-f0-9]{64}"));
        verify(imports).insert(org.mockito.ArgumentMatchers.argThat(job ->
                job.previewHash().equals(preview.previewHash()) && job.projectVersion() == 7));

        assertThrows(IllegalArgumentException.class, () -> service.preview(
                new ImportApplicationService.CreatePreview(41L, 10L, 10L, "zh-CN",
                        "request-0002", "{\"home.title\":\"a\",\"home.title\":\"b\"}")));
    }
}
