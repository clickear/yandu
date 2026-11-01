package com.yandu.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.audit.AuditLog;
import com.yandu.release.application.ReleaseRepository;
import com.yandu.release.application.ReleaseRepository.ReleaseLine;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReleaseTest {
    private ReleaseRepository repository;
    private WorkspaceApplicationService workspace;
    private ProjectAccessPolicy access;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private ReleaseApplicationService service;
    private final long[] nextId = {300L};

    @BeforeEach
    void setUp() {
        repository = mock(ReleaseRepository.class);
        workspace = mock(WorkspaceApplicationService.class);
        access = mock(ProjectAccessPolicy.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        nextId[0] = 300L;
        given(workspace.getProject(41L, 10L)).willReturn(new WorkspaceApplicationService.ProjectView(
                "10", "41", "Store", "zh-CN", Set.of("en-US"), 1, "11"));
        service = new ReleaseApplicationService(repository, workspace, access, () -> nextId[0]++,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                new ObjectMapper(), mock(AuditLog.class), Runnable::run,
                testTransactionManager(), eventPublisher);
    }

    @Test
    void queuesReleaseOnceForAnIdempotentRequest() {
        var job = new ReleaseRepository.ReleaseJob(300L, 10L, "en-US", "json-flat-v1",
                "publish-1", java.util.HexFormat.of().formatHex(sha256("en-US\njson-flat-v1")),
                "QUEUED", null, null, 41L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        given(repository.findJobByKey(10L, 41L, "publish-1"))
                .willReturn(Optional.empty(), Optional.of(job));
        org.mockito.Mockito.doNothing().when(repository).insertJob(org.mockito.ArgumentMatchers.any());

        var first = service.requestPublish(new ReleaseApplicationService.PublishRelease(
                41L, 10L, "en-US", ReleaseApplicationService.JSON_FLAT), "publish-1");
        var repeated = service.requestPublish(new ReleaseApplicationService.PublishRelease(
                41L, 10L, "en-US", ReleaseApplicationService.JSON_FLAT), "publish-1");

        assertEquals(first.id(), repeated.id());
        assertEquals("QUEUED", repeated.status());
        verify(repository).insertJob(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void queuedReleaseBuildsSnapshotAndCompletesJob() {
        var queued = new ReleaseRepository.ReleaseJob(70L, 10L, "en-US", "json-flat-v1",
                "publish-1", "hash", "QUEUED", null, null, 41L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        var running = new ReleaseRepository.ReleaseJob(70L, 10L, "en-US", "json-flat-v1",
                "publish-1", "hash", "RUNNING", null, null, 41L,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        given(repository.findQueuedJobs(20)).willReturn(List.of(queued));
        given(repository.claimJob(org.mockito.ArgumentMatchers.eq(70L), org.mockito.ArgumentMatchers.any()))
                .willReturn(true);
        given(repository.findJobForUpdate(10L, 70L)).willReturn(Optional.of(running));
        given(repository.lines(10L, "en-US")).willReturn(List.of(new ReleaseLine(20L, "home.title",
                "Hello", 3, "Bonjour", "APPROVED", 3, 2)));
        given(repository.create(org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("en-US"),
                org.mockito.ArgumentMatchers.eq(ReleaseApplicationService.JSON_FLAT),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> new ReleaseRepository.ReleaseSnapshot(300L, 10L, "en-US",
                        call.getArgument(3), 1, null, call.getArgument(4), call.getArgument(5),
                        call.getArgument(7), 41L, Instant.parse("2026-01-01T00:00:00Z")));
        given(repository.completeJob(org.mockito.ArgumentMatchers.eq(70L),
                org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.any())).willReturn(true);

        service.dispatchQueuedJobs();

        verify(repository).completeJob(org.mockito.ArgumentMatchers.eq(70L),
                org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.any());
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(ReleasePublishedEvent.class));
    }

    @Test
    void nettyConsumerReceivesInitialVersionAndCommittedChangeNotices() throws Exception {
        int port;
        try (var freePort = new ServerSocket(0)) { port = freePort.getLocalPort(); }
        var hub = new com.yandu.web.ReleaseChangeHub();
        var releases = mock(ReleaseApplicationService.class);
        given(releases.latestPublished(1001L, "fr-FR", "json-flat-v1")).willReturn(
                new ReleaseApplicationService.PublicReleaseMetadata("42", "1001", "fr-FR", "json-flat-v1",
                        7, "checksum-1", Instant.parse("2026-10-31T00:00:00Z")));
        var server = new com.yandu.web.netty.NettyReleaseSyncServer(new ObjectMapper(), hub, releases, port);
        server.start();
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            var writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer.println("""
                    {"type":"subscribe","projectId":"1001","locale":"fr-FR","format":"json-flat-v1"}
                    """.strip());
            var initial = new ObjectMapper().readTree(reader.readLine());
            assertEquals("release-changed", initial.path("type").asText());
            assertEquals("checksum-1", initial.path("metadata").path("checksumSha256").asText());

            hub.onPublished(new ReleasePublishedEvent(1001L, "fr-FR", "json-flat-v1", 43L, 8,
                    "checksum-2", Instant.parse("2026-10-31T00:01:00Z")));
            var changed = new ObjectMapper().readTree(reader.readLine());
            assertEquals("checksum-2", changed.path("metadata").path("checksumSha256").asText());
        } finally {
            server.stop();
        }
    }

    private static org.springframework.transaction.PlatformTransactionManager testTransactionManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) { }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { }
        };
    }

    @Test
    void publishesJavaPropertiesAsDeterministicAsciiReadableByTheJdk() throws Exception {
        var command = new ReleaseApplicationService.PublishRelease(41L, 10L, "en-US",
                ReleaseApplicationService.JAVA_PROPERTIES);
        given(repository.lines(10L, "en-US")).willReturn(List.of(
                new ReleaseLine(20L, "a key=雪", "source", 3,
                        "  café\\x\n", "APPROVED", 3, 2)));
        given(repository.create(org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("en-US"),
                org.mockito.ArgumentMatchers.eq(ReleaseApplicationService.JAVA_PROPERTIES),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> new ReleaseRepository.ReleaseSnapshot(300L, 10L, "en-US",
                        call.getArgument(3), 1, null, call.getArgument(4), call.getArgument(5),
                        call.getArgument(7), 41L, Instant.parse("2026-01-01T00:00:00Z")));

        var published = service.publish(command);
        var properties = new Properties();
        properties.load(new ByteArrayInputStream(published.content().getBytes(StandardCharsets.US_ASCII)));

        assertEquals("java-properties-v1", published.format());
        assertEquals("  café\\x\n", properties.getProperty("a key=雪"));
        assertTrue(published.content().chars().allMatch(character -> character <= 0x7f));
        assertTrue(published.content().contains("a\\ key\\=\\u96EA="));
        assertTrue(published.content().contains("\\\\x\\n\n"));
    }

    @Test
    void publishesSnapshotAndBlocksStaleTranslations() {
        var command = new ReleaseApplicationService.PublishRelease(41L, 10L, "en-US");
        given(repository.lines(10L, "en-US")).willReturn(List.of(
                new ReleaseLine(20L, "home.title", "Hello {user}", 3,
                        "Bonjour {user}", "APPROVED", 3, 2)));
        given(repository.create(org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("en-US"),
                org.mockito.ArgumentMatchers.eq(ReleaseApplicationService.JSON_FLAT),
                org.mockito.ArgumentMatchers.eq("{\"home.title\":\"Bonjour {user}\"}\n"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.any())).willAnswer(call ->
                new ReleaseRepository.ReleaseSnapshot(300L, 10L, "en-US", ReleaseApplicationService.JSON_FLAT,
                        1, null, call.getArgument(4), call.getArgument(5), call.getArgument(7), 41L,
                        Instant.parse("2026-01-01T00:00:00Z")));

        var published = service.publish(command);

        assertEquals("{\"home.title\":\"Bonjour {user}\"}\n", published.content());
        assertEquals(64, published.checksumSha256().length());
        assertEquals(1, published.entryCount());
        assertEquals(null, published.rollbackOfReleaseId());
        verify(access).requireCanPublish(41L, 10L);

        given(repository.lines(10L, "en-US")).willReturn(List.of(
                new ReleaseLine(20L, "home.title", "Hello {user}", 4,
                        "Bonjour {user}", "APPROVED", 3, 2)));
        assertThrows(ReleaseApplicationService.ReleaseGateException.class,
                () -> service.publish(command));
    }

    @Test
    void rollbackCopiesOldSnapshotAsANewLinkedRelease() {
        var content = "{\"home.title\":\"Bonjour {user}\"}\n";
        var manifest = "{\"locale\":\"en-US\"}";
        var checksum = java.util.HexFormat.of().formatHex(sha256(content));
        var previous = new ReleaseRepository.ReleaseSnapshot(200L, 10L, "en-US", ReleaseApplicationService.JSON_FLAT, 3,
                null, content, manifest, checksum, 41L, Instant.parse("2025-12-01T00:00:00Z"));
        given(repository.find(10L, 200L)).willReturn(Optional.of(previous));
        given(repository.create(org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("en-US"),
                org.mockito.ArgumentMatchers.eq(ReleaseApplicationService.JSON_FLAT),
                org.mockito.ArgumentMatchers.eq(content), org.mockito.ArgumentMatchers.eq(manifest),
                org.mockito.ArgumentMatchers.eq(200L), org.mockito.ArgumentMatchers.eq(checksum),
                org.mockito.ArgumentMatchers.eq(41L), org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> new ReleaseRepository.ReleaseSnapshot(300L, 10L, "en-US", ReleaseApplicationService.JSON_FLAT, 4,
                        200L, content, manifest, checksum, 41L,
                        Instant.parse("2026-01-01T00:00:00Z")));

        var rollback = service.rollback(41L, 10L, 200L);

        assertEquals("300", rollback.id());
        assertEquals("200", rollback.rollbackOfReleaseId());
        assertEquals(content, rollback.content());
        assertEquals(checksum, rollback.checksumSha256());
    }

    @Test
    void releaseHistoryReturnsVerifiedSnapshotMetadata() {
        var content = "{\"home.title\":\"Bonjour\"}\n";
        var checksum = java.util.HexFormat.of().formatHex(sha256(content));
        given(repository.list(10L, "en-US", null, 100)).willReturn(List.of(
                new ReleaseRepository.ReleaseSnapshot(200L, 10L, "en-US",
                        ReleaseApplicationService.JSON_FLAT, 2, null, content, "{}", checksum,
                        41L, Instant.parse("2025-12-01T00:00:00Z"))));

        var history = service.historySummaries(41L, 10L, "en-US", null);

        assertEquals(1, history.size());
        assertEquals("200", history.getFirst().id());
        assertEquals(2, history.getFirst().version());
        assertEquals(checksum, history.getFirst().checksumSha256());
        assertEquals(1, history.getFirst().entryCount());
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
