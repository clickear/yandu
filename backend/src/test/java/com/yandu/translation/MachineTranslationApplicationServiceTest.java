package com.yandu.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yandu.audit.AuditLog;
import com.yandu.translation.application.MachineTranslationProvider;
import com.yandu.translation.application.MachineTranslationRepository;
import com.yandu.translation.infrastructure.http.LibreTranslateProvider;
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
    private final MachineTranslationApplicationService service = new MachineTranslationApplicationService(
            jobs, provider, translations, workspace, access, () -> 500L,
            java.time.Clock.systemUTC(), audit, mock(TransactionTemplate.class), mock(TaskExecutor.class));

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
