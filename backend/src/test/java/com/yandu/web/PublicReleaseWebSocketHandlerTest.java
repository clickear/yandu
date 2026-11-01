package com.yandu.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.release.ReleasePublishedEvent;
import java.time.Instant;
import java.io.IOException;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class PublicReleaseWebSocketHandlerTest {

    @Test
    void websocketSubscriptionGetsCurrentVersionAndLaterCommitNotice() throws Exception {
        var session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("consumer-1");
        given(session.isOpen()).willReturn(true);
        given(session.getAttributes()).willReturn(new HashMap<>());
        var releases = mock(ReleaseApplicationService.class);
        given(releases.latestPublished(1001L, "fr-FR", "json-flat-v1")).willReturn(
                new ReleaseApplicationService.PublicReleaseMetadata("42", "1001", "fr-FR", "json-flat-v1",
                        7, "checksum-1", Instant.parse("2026-10-31T00:00:00Z")));
        var hub = new ReleaseChangeHub();
        var handler = new PublicReleaseWebSocketHandler(new ObjectMapper(), hub, releases);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"subscribe","projectId":"1001","locale":"fr-FR","format":"json-flat-v1"}
                """.strip()));
        hub.onPublished(new ReleasePublishedEvent(1001L, "fr-FR", "json-flat-v1", 43L, 8,
                "checksum-2", Instant.parse("2026-10-31T00:01:00Z")));

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
        var handler = new PublicReleaseWebSocketHandler(new ObjectMapper(), new ReleaseChangeHub(),
                mock(ReleaseApplicationService.class));

        handler.afterConnectionEstablished(session);
        handler.sendHeartbeats();
        handler.sendHeartbeats();

        verify(session).close(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
        verify(session, times(1)).sendMessage(org.mockito.ArgumentMatchers.any());
    }
}
