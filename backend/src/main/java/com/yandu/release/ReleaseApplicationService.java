package com.yandu.release;

import com.yandu.audit.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.release.application.ReleaseRepository;
import com.yandu.release.application.ReleaseRepository.ReleaseLine;
import com.yandu.release.application.ReleaseRepository.ReleaseSnapshot;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds, stores and reads immutable localization release snapshots. */
@Service
public class ReleaseApplicationService {

    public static final String JSON_FLAT = "json-flat-v1";
    public static final String JAVA_PROPERTIES = "java-properties-v1";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.-]*)}");
    private final ReleaseRepository releases;
    private final WorkspaceApplicationService workspace;
    private final ProjectAccessPolicy access;
    private final LongSupplier ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final AuditLog audit;

    public ReleaseApplicationService(ReleaseRepository releases, WorkspaceApplicationService workspace,
                                    ProjectAccessPolicy access, LongSupplier ids, Clock clock,
                                    ObjectMapper json, AuditLog audit) {
        this.releases = Objects.requireNonNull(releases);
        this.workspace = Objects.requireNonNull(workspace);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public ReleaseView publish(PublishRelease command) {
        access.requireCanPublish(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        var locale = WorkspaceApplicationService.normalizeLocale(command.locale());
        var format = normalizeFormat(command.format());
        if (!project.targetLocales().contains(locale)) {
            throw new IllegalArgumentException("Release locale is not configured for this project");
        }

        var lines = releases.lines(command.projectId(), locale);
        if (lines.isEmpty()) throw new ReleaseGateException(List.of("项目没有可发布的资源条目"));
        var content = new TreeMap<String, String>();
        var failures = new java.util.ArrayList<String>();
        var manifestEntries = new java.util.ArrayList<ManifestEntry>();
        for (var line : lines) {
            validate(line, locale, failures);
            if (content.putIfAbsent(line.key(), line.translation()) != null) {
                failures.add("资源键在多个命名空间中重复: " + line.key());
            }
            manifestEntries.add(new ManifestEntry(Long.toString(line.entryId()), line.key(),
                    line.sourceRevision(), line.translationVersion()));
        }
        if (!failures.isEmpty()) throw new ReleaseGateException(failures);

        String snapshot;
        String manifest;
        try {
            snapshot = JSON_FLAT.equals(format) ? json.writeValueAsString(content) + "\n"
                    : properties(content);
            manifest = json.writeValueAsString(new ReleaseManifest(format, locale,
                    manifestEntries, List.of("PLACEHOLDER_MISMATCH")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize release snapshot", exception);
        }
        var checksum = sha256(snapshot);
        var stored = releases.create(ids.getAsLong(), command.projectId(), locale, format,
                snapshot, manifest, null, checksum, command.actorId(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "RELEASE_PUBLISHED", "RELEASE",
                stored.id(), java.util.Map.of("locale", locale, "format", format, "version", stored.version(),
                        "checksumSha256", checksum, "entryCount", content.size()));
        return view(stored, content.size());
    }

    @Transactional
    public ReleaseView rollback(long actorId, long projectId, long releaseId) {
        access.requireCanPublish(actorId, projectId);
        workspace.getProject(actorId, projectId);
        var previous = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(previous);
        var stored = releases.create(ids.getAsLong(), projectId, previous.locale(), previous.format(),
                previous.content(), previous.manifest(), previous.id(), previous.checksum(),
                actorId, clock.instant());
        audit.record(projectId, actorId, "RELEASE_ROLLED_BACK", "RELEASE", stored.id(),
                java.util.Map.of("locale", stored.locale(), "version", stored.version(),
                        "rollbackOfReleaseId", previous.id(), "checksumSha256", stored.checksum()));
        return view(stored, countEntries(stored));
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> history(long actorId, long projectId, String locale, String format) {
        access.requireCanReadProject(actorId, projectId);
        var normalizedLocale = locale == null ? null : WorkspaceApplicationService.normalizeLocale(locale);
        var normalizedFormat = format == null ? null : normalizeFormat(format);
        return releases.list(projectId, normalizedLocale, normalizedFormat, 100).stream()
                .map(snapshot -> view(verify(snapshot), countEntries(snapshot)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReleaseView get(long actorId, long projectId, long releaseId) {
        access.requireCanReadProject(actorId, projectId);
        var snapshot = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(snapshot);
        return view(snapshot, countEntries(snapshot));
    }

    @Transactional
    public String download(long actorId, long projectId, long releaseId) {
        access.requireCanReadProject(actorId, projectId);
        var snapshot = releases.find(projectId, releaseId)
                .orElseThrow(() -> new ReleaseNotFoundException(releaseId));
        verify(snapshot);
        audit.record(projectId, actorId, "RELEASE_DOWNLOADED", "RELEASE", releaseId,
                java.util.Map.of("locale", snapshot.locale(), "version", snapshot.version(),
                        "checksumSha256", snapshot.checksum()));
        return snapshot.content();
    }

    private static void validate(ReleaseLine line, String locale, List<String> failures) {
        if (!"APPROVED".equals(line.status())) {
            failures.add(line.key() + "（" + locale + "）：译文尚未审核通过");
            return;
        }
        if (line.translationSourceRevision() != line.sourceRevision()) {
            failures.add(line.key() + "（" + locale + "）：译文基于旧版源文");
            return;
        }
        if (!placeholders(line.sourceText()).equals(placeholders(line.translation()))) {
            failures.add(line.key() + "（" + locale + "）：命名占位符与源文不一致");
        }
    }

    private static java.util.Map<String, Integer> placeholders(String value) {
        var result = new java.util.TreeMap<String, Integer>();
        var matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) result.merge(matcher.group(1), 1, Integer::sum);
        return result;
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isBlank()) return JSON_FLAT;
        if (!JSON_FLAT.equals(format) && !JAVA_PROPERTIES.equals(format)) {
            throw new IllegalArgumentException("format must be json-flat-v1 or java-properties-v1");
        }
        return format;
    }

    /** Emits stable ASCII accepted by Java Properties.load(InputStream). */
    private static String properties(TreeMap<String, String> values) {
        var output = new StringBuilder();
        values.forEach((key, value) -> output.append(escapeProperty(key, true))
                .append('=').append(escapeProperty(value, false)).append('\n'));
        return output.toString();
    }

    private static String escapeProperty(String value, boolean key) {
        var output = new StringBuilder(value.length());
        boolean leadingValueSpace = !key;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ' ' && (key || leadingValueSpace)) output.append("\\ ");
            else appendPropertyChar(output, character, key);
            if (!key && character != ' ') leadingValueSpace = false;
        }
        return output.toString();
    }

    private static void appendPropertyChar(StringBuilder output, char character, boolean key) {
        switch (character) {
            case '\\' -> output.append("\\\\");
            case '\t' -> output.append("\\t");
            case '\n' -> output.append("\\n");
            case '\r' -> output.append("\\r");
            case '\f' -> output.append("\\f");
            case '=', ':' -> {
                if (key) output.append('\\');
                output.append(character);
            }
            case '#', '!' -> {
                if (key) output.append('\\');
                output.append(character);
            }
            default -> {
                if (character < 0x20 || character > 0x7e) {
                    output.append("\\u");
                    var hex = Integer.toHexString(character).toUpperCase(java.util.Locale.ROOT);
                    output.append("0".repeat(4 - hex.length())).append(hex);
                } else output.append(character);
            }
        }
    }

    private int countEntries(ReleaseSnapshot snapshot) {
        if (JAVA_PROPERTIES.equals(snapshot.format())) {
            return (int) snapshot.content().lines().filter(line -> !line.isBlank()).count();
        }
        try {
            return json.readTree(snapshot.content()).size();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored release snapshot is invalid", exception);
        }
    }

    private ReleaseSnapshot verify(ReleaseSnapshot snapshot) {
        if (!sha256(snapshot.content()).equals(snapshot.checksum())) {
            throw new IllegalStateException("Stored release checksum does not match its content");
        }
        return snapshot;
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ReleaseView view(ReleaseSnapshot snapshot, int entryCount) {
        return new ReleaseView(Long.toString(snapshot.id()), Long.toString(snapshot.projectId()),
                snapshot.locale(), snapshot.format(), snapshot.version(), snapshot.rollbackOfReleaseId() == null
                        ? null : Long.toString(snapshot.rollbackOfReleaseId()),
                snapshot.content(), snapshot.manifest(),
                snapshot.checksum(), entryCount, Long.toString(snapshot.publishedBy()), snapshot.publishedAt());
    }

    public record PublishRelease(long actorId, long projectId, String locale, String format) {
        public PublishRelease(long actorId, long projectId, String locale) {
            this(actorId, projectId, locale, JSON_FLAT);
        }

        public PublishRelease {
            if (actorId <= 0 || projectId <= 0 || locale == null || locale.isBlank()) {
                throw new IllegalArgumentException("actorId, projectId and locale are required");
            }
            format = normalizeFormat(format);
        }
    }

    public record ReleaseView(String id, String projectId, String locale, String format, long version,
                              String rollbackOfReleaseId,
                              String content, String manifestJson, String checksumSha256, int entryCount,
                              String publishedBy, java.time.Instant publishedAt) { }

    public record ReleaseManifest(String format, String locale, List<ManifestEntry> entries,
                                  List<String> passedQualityRules) { }

    public record ManifestEntry(String entryId, String key, long sourceRevision,
                                long translationVersion) { }

    public static final class ReleaseGateException extends RuntimeException {
        private final List<String> failures;
        public ReleaseGateException(List<String> failures) {
            super("Release quality gate failed");
            this.failures = List.copyOf(failures);
        }
        public List<String> failures() { return failures; }
    }

    public static final class ReleaseNotFoundException extends RuntimeException {
        public ReleaseNotFoundException(long releaseId) {
            super("Release not found: " + releaseId);
        }
    }
}
