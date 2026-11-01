package com.yandu.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.release.ReleasePublishedEvent;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class PublicReleaseWebSocketHandler extends TextWebSocketHandler {

    private static final String REGISTRATION = PublicReleaseWebSocketHandler.class.getName() + ".registration";
    private final ObjectMapper json;
    private final ReleaseChangeHub hub;
    private final ReleaseApplicationService releases;
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public PublicReleaseWebSocketHandler(ObjectMapper json, ReleaseChangeHub hub,
                                         ReleaseApplicationService releases) {
        this.json = json;
        this.hub = hub;
        this.releases = releases;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        try {
            var subscription = ReleaseSyncProtocol.subscription(json.readTree(message.getPayload()));
            closeRegistration(session);
            var registration = hub.subscribe(subscription.projectId(), subscription.locale(), subscription.format(),
                    event -> sendChanged(session, event));
            session.getAttributes().put(REGISTRATION, registration);
            try {
                var latest = releases.latestPublished(subscription.projectId(), subscription.locale(),
                        subscription.format());
                if (!latest.checksumSha256().equals(subscription.afterChecksum())) {
                    send(session, ReleaseSyncProtocol.changed(latest));
                }
            } catch (ReleaseApplicationService.ReleaseNotFoundException noReleaseYet) {
                // The subscription remains open and will receive the first published release.
            }
        } catch (IOException transportFailure) {
            closeSession(session);
        } catch (RuntimeException invalid) {
            send(session, java.util.Map.of("type", "error", "message", "Invalid subscription request"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        closeRegistration(session);
    }

    @Scheduled(fixedDelayString = "${yandu.sync.websocket.heartbeat-ms:25000}")
    public void sendHeartbeats() {
        sessions.values().forEach(session -> {
            try { send(session, java.util.Map.of("type", "heartbeat")); }
            catch (IOException failure) { closeSession(session); }
        });
    }

    private void sendChanged(WebSocketSession session, ReleasePublishedEvent event) {
        try {
            send(session, ReleaseSyncProtocol.changed(event));
        } catch (IOException | RuntimeException failure) {
            closeSession(session);
        }
    }

    private void closeSession(WebSocketSession session) {
        sessions.remove(session.getId(), session);
        closeRegistration(session);
        try {
            if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // The transport has already failed; removing its listener is the important cleanup.
        }
    }

    private void send(WebSocketSession session, Object message) throws IOException {
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(json.writeValueAsString(message)));
        }
    }

    private static void closeRegistration(WebSocketSession session) {
        var registration = session.getAttributes().remove(REGISTRATION);
        if (registration instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }
}
