package com.yandu.sync.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.sync.ReleaseSyncService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NettyReleaseSyncServerTest {

    @Test
    void subscriberGetsCurrentVersionAndCommittedChangeNotices() throws Exception {
        int port;
        try (var freePort = new ServerSocket(0)) { port = freePort.getLocalPort(); }
        var releases = mock(ReleaseSyncService.class);
        var listener = new AtomicReference<java.util.function.Consumer<ReleaseSyncService.ReleaseNotice>>();
        given(releases.subscribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> { listener.set(call.getArgument(1)); return (AutoCloseable) () -> { }; });
        given(releases.latest(org.mockito.ArgumentMatchers.any())).willReturn(Optional.of(notice(7, "checksum-1")));
        var server = new NettyReleaseSyncServer(new ObjectMapper(), releases, port);
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

            listener.get().accept(notice(8, "checksum-2"));
            var changed = new ObjectMapper().readTree(reader.readLine());
            assertEquals("checksum-2", changed.path("metadata").path("checksumSha256").asText());
        } finally {
            server.stop();
        }
    }

    private static ReleaseSyncService.ReleaseNotice notice(long version, String checksum) {
        return new ReleaseSyncService.ReleaseNotice(Long.toString(version), 1001L, "fr-FR", "json-flat-v1",
                version, checksum, Instant.parse("2026-10-31T00:00:00Z"));
    }
}
