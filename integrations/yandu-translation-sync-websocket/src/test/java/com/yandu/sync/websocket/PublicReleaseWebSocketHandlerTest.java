package com.yandu.sync.websocket;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.sync.ReleaseSyncService;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class PublicReleaseWebSocketHandlerTest {

    @Test
    void subscriptionGetsCurrentVersionAndLaterCommitNotice() throws Exception {
        var session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("consumer-1");
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>());
        var releases = mock(ReleaseSyncService.class);
        var listener = new AtomicReference<java.util.function.Consumer<ReleaseSyncService.ReleaseNotice>>();
        given(releases.subscribe(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willAnswer(call -> { listener.set(call.getArgument(1)); return (AutoCloseable) () -> { }; });
        given(releases.latest(org.mockito.ArgumentMatchers.any())).willReturn(Optional.of(notice(7, "checksum-1")));
        var handler = new PublicReleaseWebSocketHandler(new ObjectMapper(), releases);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"subscribe","projectId":"1001","locale":"fr-FR","format":"json-flat-v1"}
                """.strip()));
        listener.get().accept(notice(8, "checksum-2"));

        verify(session, times(2)).sendMessage(argThat(message ->
                ((TextMessage) message).getPayload().contains("checksum-2")
                        || ((TextMessage) message).getPayload().contains("checksum-1")));
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
    }

    @Test
    void failedHeartbeatClosesAndRemovesTheBrokenSession() throws Exception {
        var session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("broken-consumer");
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>());
        doThrow(new IOException("socket closed")).when(session).sendMessage(org.mockito.ArgumentMatchers.any());
        var handler = new PublicReleaseWebSocketHandler(new ObjectMapper(), mock(ReleaseSyncService.class));

        handler.afterConnectionEstablished(session);
        handler.sendHeartbeats();
        handler.sendHeartbeats();

        verify(session).close(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
        verify(session, times(1)).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    static ReleaseSyncService.ReleaseNotice notice(long version, String checksum) {
        return new ReleaseSyncService.ReleaseNotice(Long.toString(version), 1001L, "fr-FR", "json-flat-v1",
                version, checksum, Instant.parse("2026-10-31T00:00:00Z"));
    }
}
