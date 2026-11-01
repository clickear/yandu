package com.yandu.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yandu.audit.AuditLog;
import com.yandu.translation.application.MachineTranslationProvider;
import com.yandu.translation.application.MachineTranslationRepository;
import com.yandu.translation.infrastructure.http.LibreTranslateProvider;
import com.yandu.translation.infrastructure.http.OpenAiCompatibleProvider;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HexFormat;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;

class MachineTranslationApplicationServiceTest {

    private final MachineTranslationRepository jobs = mock(MachineTranslationRepository.class);
    private final MachineTranslationProvider provider = mock(MachineTranslationProvider.class);
    private final TranslationApplicationService translations = mock(TranslationApplicationService.class);
    private final WorkspaceApplicationService workspace = mock(WorkspaceApplicationService.class);
    private final ProjectAccessPolicy access = mock(ProjectAccessPolicy.class);
    private final AuditLog audit = mock(AuditLog.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final MachineTranslationApplicationService service = new MachineTranslationApplicationService(
            jobs, provider, translations, workspace, access, () -> 500L,
            java.time.Clock.systemUTC(), audit, transactions, executor);

    @Test
    void refusesToCreateATaskWhenNoProviderIsConfigured() {
        project();
        when(jobs.findByIdempotencyKey(101L, 11L, "request-123")).thenReturn(Optional.empty());
        when(provider.isConfigured()).thenReturn(false);

        assertThrows(MachineTranslationApplicationService.ProviderNotConfiguredException.class,
                () -> service.create(new MachineTranslationApplicationService.CreateJob(
                        11L, 101L, "en-US", "request-123", List.of())));

        verify(jobs, never()).create(any());
    }

    @Test
    void refusesToRequeueFailedItemsWhenNoProviderIsConfigured() {
        when(provider.isConfigured()).thenReturn(false);

        assertThrows(MachineTranslationApplicationService.ProviderNotConfiguredException.class,
                () -> service.retry(new MachineTranslationApplicationService.RetryJob(11L, 101L, 900L)));

        verify(jobs, never()).retryFailed(anyLong(), anyLong(), any());
    }

    @Test
    void returnsTheExistingJobForAnIdenticalIdempotentRequest() {
        project();
        var hash = requestHash("en-US");
        when(jobs.findByIdempotencyKey(101L, 11L, "request-123")).thenReturn(Optional.of(
                new MachineTranslationRepository.Job(900L, 101L, "en-US", "RUNNING", 3,
                        1, 0, 0, 11L, hash, Instant.EPOCH, Instant.EPOCH, null)));
        when(provider.isConfigured()).thenReturn(false);

        var result = service.create(new MachineTranslationApplicationService.CreateJob(
                11L, 101L, "en-US", "request-123", List.of()));

        assertEquals("900", result.id());
        assertEquals("RUNNING", result.status());
        verify(provider, never()).translate(any(), any(), any());
        verify(jobs, never()).create(any());
    }

    @Test
    void includesNamespaceIdWhenListingTaskItemsForWorkspaceNavigation() {
        when(jobs.find(101L, 900L)).thenReturn(Optional.of(new MachineTranslationRepository.Job(
                900L, 101L, "en-US", "COMPLETED", 1, 1, 0, 0, 11L,
                "request-hash", Instant.EPOCH, Instant.EPOCH, null)));
        when(jobs.items(101L, 900L, 0, 50)).thenReturn(List.of(new MachineTranslationRepository.JobItem(
                900L, 101L, "zh-CN", "en-US", 501L, 301L, "home.title",
                "欢迎", 1, null, 11L, 1, "COMPLETED", null)));
        when(jobs.itemCount(101L, 900L)).thenReturn(1L);

        var page = service.items(11L, 101L, 900L, 1, 50);

        assertEquals("301", page.items().getFirst().namespaceId());
        assertEquals("home.title", page.items().getFirst().key());
    }

    @Test
    void lateWorkerResultDoesNotWriteDraftAfterItsClaimWasRecovered() {
        var item = claimedItem(2);
        var worker = new AtomicReference<Runnable>();
        when(provider.isConfigured()).thenReturn(true);
        when(jobs.claimNext(any())).thenReturn(Optional.of(item));
        doAnswer(invocation -> { worker.set(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        when(provider.translate("你好", "zh-CN", "en-US")).thenReturn("Hello");
        when(jobs.lockCurrentClaim(item)).thenReturn(false);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        service.dispatchQueuedItems();
        worker.get().run();

        verify(jobs).lockCurrentClaim(item);
        verify(translations, never()).saveMachineDraft(any());
        verify(jobs, never()).completeItem(anyLong(), anyLong(), anyInt(), any(String.class), any(), any());
    }

    @Test
    void currentWorkerPersistsDraftAndCompletesItsClaimGeneration() {
        var item = claimedItem(3);
        var worker = new AtomicReference<Runnable>();
        when(provider.isConfigured()).thenReturn(true);
        when(jobs.claimNext(any())).thenReturn(Optional.of(item));
        doAnswer(invocation -> { worker.set(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        when(provider.translate("你好", "zh-CN", "en-US")).thenReturn("Hello");
        when(jobs.lockCurrentClaim(item)).thenReturn(true);
        when(translations.saveMachineDraft(any())).thenReturn(TranslationApplicationService.MachineDraftResult.APPLIED);
        when(jobs.completeItem(org.mockito.ArgumentMatchers.eq(900L), org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq("COMPLETED"),
                org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(true);
        doAnswer(invocation -> {
            java.util.function.Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        service.dispatchQueuedItems();
        worker.get().run();

        verify(translations).saveMachineDraft(any());
        verify(jobs).completeItem(org.mockito.ArgumentMatchers.eq(900L), org.mockito.ArgumentMatchers.eq(501L),
                org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq("COMPLETED"),
                org.mockito.ArgumentMatchers.isNull(), any());
    }

    @Test
    void emptyProviderResultFailsTheItemWithoutCreatingAnEmptyDraft() {
        var item = claimedItem(1);
        var worker = new AtomicReference<Runnable>();
        when(provider.isConfigured()).thenReturn(true);
        when(jobs.claimNext(any())).thenReturn(Optional.of(item));
        doAnswer(invocation -> { worker.set(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        when(provider.translate("你好", "zh-CN", "en-US")).thenReturn("  \n");

        service.dispatchQueuedItems();
        worker.get().run();

        verify(translations, never()).saveMachineDraft(any());
        verify(jobs).completeItem(org.mockito.ArgumentMatchers.eq(900L),
                org.mockito.ArgumentMatchers.eq(501L), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq("FAILED"),
                org.mockito.ArgumentMatchers.eq("机器翻译服务返回了空译文，请检查服务后重试。"), any());
    }

    private static MachineTranslationRepository.JobItem claimedItem(int attempt) {
        return new MachineTranslationRepository.JobItem(900L, 101L, "zh-CN", "en-US", 501L,
                301L, "home.title", "你好", 1L, null, 11L, attempt, "RUNNING", null);
    }

    @Test
    void libreTranslateAdapterSendsLocaleLanguageCodesAndParsesTheResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestBody = new AtomicReference<String>();
        server.createContext("/translate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var response = "{\"translatedText\":\"Hello\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new LibreTranslateProvider(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", 5000);

            assertEquals("Hello", adapter.translate("你好", "zh-CN", "en-US"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"source\":\"zh\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"target\":\"en\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"api_key\":\"test-secret\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openAiCompatibleAdapterSendsModelAndTranslationPromptToChatCompletions() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var response = "{\"choices\":[{\"message\":{\"content\":\"Hello {name}\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var adapter = new OpenAiCompatibleProvider(RestClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/",
                    "server-secret", "translation-model", 5000);

            assertEquals("Hello {name}", adapter.translate("你好 {name}", "zh-CN", "en-US"));
            assertEquals("Bearer server-secret", authorization.get());
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"model\":\"translation-model\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("\"content\":\"你好 {name}\""));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("zh-CN"));
            org.junit.jupiter.api.Assertions.assertTrue(requestBody.get().contains("en-US"));
        } finally {
            server.stop(0);
        }
    }

    private void project() {
        when(workspace.getProject(11L, 101L)).thenReturn(new WorkspaceApplicationService.ProjectView(
                "101", "11", "Project", "zh-CN", Set.of("en-US"), 1,
                "101", "", "ACTIVE"));
    }

    private static String requestHash(String locale) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((locale + "\nALL_UNTRANSLATED").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
