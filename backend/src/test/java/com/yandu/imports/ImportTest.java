package com.yandu.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.springframework.dao.DataIntegrityViolationException;
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
        given(catalog.searchProject(any())).willReturn(new CatalogEntryApplicationService.EntryPage(1, 100, 1,
                List.of(new CatalogEntryApplicationService.EntryView(
                        "20", "10", "11", "home.title", "旧文案", 3, 3, false))));
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

    @Test
    void recentJobSummaryExposesStatusAndCountsWithoutReturningImportContents() {
        var preview = service.preview(new ImportApplicationService.CreatePreview(41L, 10L, 10L,
                "zh-CN", "request-0001", "{\"home.title\":\"新文案\"}"));
        var captured = org.mockito.ArgumentCaptor.forClass(ImportRepository.Job.class);
        verify(imports).insert(captured.capture());
        var job = captured.getValue();
        given(imports.findRecent(10L, 20)).willReturn(List.of(new ImportRepository.JobSummary(
                job.id(), job.locale(), job.status(), job.previewHash(), 0, 1, 0, 0,
                null, job.createdAt(), job.updatedAt())));

        var jobs = service.listRecent(41L, 10L);

        assertEquals(1, jobs.size());
        assertEquals(preview.id(), jobs.getFirst().id());
        assertEquals("PREVIEW_READY", jobs.getFirst().status());
        assertEquals(1, jobs.getFirst().updated());
        assertEquals(0, jobs.getFirst().conflicts());
    }

    @Test
    void concurrentIdenticalPreviewReturnsTheWinningIdempotentJob() throws Exception {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var previewData = new ImportApplicationService.PreviewData("zh-CN", "10", 7,
                List.of(), 0, 0, 0, 0);
        var winner = new ImportRepository.Job(700L, 10L, 10L, "zh-CN", 7,
                "request-0001", "{\"home.title\":\"新文案\"}",
                new ObjectMapper().writeValueAsString(previewData), "a".repeat(64),
                "PREVIEW_READY", 41L, now, now, null, 0);
        doThrow(new DataIntegrityViolationException("duplicate idempotency key"))
                .when(imports).insert(any());
        given(imports.findByIdempotencyKeyForUpdate(10L, "request-0001"))
                .willReturn(Optional.of(winner));

        var result = service.preview(new ImportApplicationService.CreatePreview(41L, 10L, 10L,
                "zh-CN", "request-0001", "{\"home.title\":\"新文案\"}"));

        assertEquals("700", result.id());
        assertEquals(winner.previewHash(), result.previewHash());
        assertEquals("PREVIEW_READY", result.status());
        verify(imports).findByIdempotencyKeyForUpdate(10L, "request-0001");
    }

    @Test
    void staleWorkerCannotApplyOrFailANewerImportAttempt() {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        var queued = new ImportRepository.Job(600L, 10L, 10L, "zh-CN", 7L,
                "request-0003", "{}", "{}", "preview-hash", "QUEUED", 41L,
                now, now, null, 0);
        var claimed = new ImportRepository.Job(600L, 10L, 10L, "zh-CN", 7L,
                "request-0003", "{}", "{}", "preview-hash", "RUNNING", 41L,
                now, now, null, 1);
        var newerAttempt = new ImportRepository.Job(600L, 10L, 10L, "zh-CN", 7L,
                "request-0003", "{}", "{}", "preview-hash", "RUNNING", 41L,
                now, now, null, 2);
        given(imports.findQueued(20)).willReturn(List.of(queued));
        given(imports.claim(600L, now)).willReturn(Optional.of(claimed));
        given(imports.findForUpdate(10L, 600L)).willReturn(Optional.of(newerAttempt));
        given(imports.fail(org.mockito.ArgumentMatchers.eq(600L), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyString(), any())).willReturn(false);
        var transactionManager = new PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) { }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { }
        };
        var serviceWithTransactions = new ImportApplicationService(imports, catalog,
                mock(TranslationApplicationService.class), mock(WorkspaceApplicationService.class),
                mock(ProjectAccessPolicy.class), () -> 900L, Clock.fixed(now, java.time.ZoneOffset.UTC),
                new ObjectMapper(), Runnable::run, transactionManager, mock(AuditLog.class));

        serviceWithTransactions.dispatchQueuedJobs();

        verify(imports).findForUpdate(10L, 600L);
        verify(imports).fail(org.mockito.ArgumentMatchers.eq(600L), org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyString(), any());
        verify(imports, never()).complete(600L, 1L, now);
        verifyNoInteractions(catalog);
    }
}
