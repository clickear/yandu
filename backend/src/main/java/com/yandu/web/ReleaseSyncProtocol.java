package com.yandu.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.release.ReleaseApplicationService.PublicReleaseMetadata;
import com.yandu.release.ReleasePublishedEvent;
import com.yandu.workspace.WorkspaceApplicationService;
import java.util.Map;

/** Small JSON contract shared by the public TCP and WebSocket change streams. */
public final class ReleaseSyncProtocol {

    private ReleaseSyncProtocol() { }

    public static Subscription subscription(JsonNode message) {
        if (!"subscribe".equals(message.path("type").asText())) {
            throw new IllegalArgumentException("The first message must have type 'subscribe'");
        }
        var projectId = message.path("projectId").asLong(0);
        if (projectId <= 0) throw new IllegalArgumentException("projectId must be positive");
        var locale = message.path("locale").asText("");
        if (locale.isBlank()) throw new IllegalArgumentException("locale is required");
        var format = message.path("format").asText(ReleaseApplicationService.JSON_FLAT);
        if (!ReleaseApplicationService.JSON_FLAT.equals(format)
                && !ReleaseApplicationService.JAVA_PROPERTIES.equals(format)) {
            throw new IllegalArgumentException("Unsupported translation format");
        }
        return new Subscription(projectId, WorkspaceApplicationService.normalizeLocale(locale), format,
                message.path("afterChecksum").asText(null));
    }

    public static Map<String, Object> changed(PublicReleaseMetadata metadata) {
        return Map.of("type", "release-changed", "metadata", Map.of(
                "id", metadata.id(), "projectId", metadata.projectId(), "locale", metadata.locale(),
                "format", metadata.format(), "version", metadata.version(),
                "checksumSha256", metadata.checksumSha256(), "publishedAt", metadata.publishedAt().toString()));
    }

    public static Map<String, Object> changed(ReleasePublishedEvent event) {
        return changed(new PublicReleaseMetadata(Long.toString(event.releaseId()),
                Long.toString(event.projectId()), event.locale(), event.format(), event.version(),
                event.checksumSha256(), event.publishedAt()));
    }

    public record Subscription(long projectId, String locale, String format, String afterChecksum) { }
}
