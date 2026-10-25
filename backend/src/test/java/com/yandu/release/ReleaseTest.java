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
    private ReleaseApplicationService service;
    private final long[] nextId = {300L};

    @BeforeEach
    void setUp() {
        repository = mock(ReleaseRepository.class);
        workspace = mock(WorkspaceApplicationService.class);
        access = mock(ProjectAccessPolicy.class);
        nextId[0] = 300L;
        given(workspace.getProject(41L, 10L)).willReturn(new WorkspaceApplicationService.ProjectView(
                "10", "41", "Store", "zh-CN", Set.of("en-US"), 1, "11"));
        service = new ReleaseApplicationService(repository, workspace, access, () -> nextId[0]++,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                new ObjectMapper(), mock(AuditLog.class));
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

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
