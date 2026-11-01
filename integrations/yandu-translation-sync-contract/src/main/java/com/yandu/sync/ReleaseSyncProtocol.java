package com.yandu.sync;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared public JSON message contract for the optional realtime server transports. */
public final class ReleaseSyncProtocol {

    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*");

    private ReleaseSyncProtocol() { }

    public static ReleaseSyncService.Subscription subscription(JsonNode message) {
        if (!"subscribe".equals(message.path("type").asText())) {
            throw new IllegalArgumentException("The first message must have type 'subscribe'");
        }
        var projectId = message.path("projectId").asLong(0);
        if (projectId <= 0) throw new IllegalArgumentException("projectId must be positive");
        var rawLocale = message.path("locale").asText("");
        if (!LOCALE.matcher(rawLocale).matches()) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        var locale = Locale.forLanguageTag(rawLocale).toLanguageTag();
        if (locale.equals("und")) throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        var format = message.path("format").asText("json-flat-v1");
        if (!"json-flat-v1".equals(format) && !"java-properties-v1".equals(format)) {
            throw new IllegalArgumentException("Unsupported translation format");
        }
        return new ReleaseSyncService.Subscription(projectId, locale, format,
                message.path("afterChecksum").asText(null));
    }

    public static Map<String, Object> changed(ReleaseSyncService.ReleaseNotice notice) {
        return Map.of("type", "release-changed", "metadata", Map.of(
                "id", notice.id(), "projectId", Long.toString(notice.projectId()),
                "locale", notice.locale(), "format", notice.format(), "version", notice.version(),
                "checksumSha256", notice.checksumSha256(), "publishedAt", notice.publishedAt().toString()));
    }
}
