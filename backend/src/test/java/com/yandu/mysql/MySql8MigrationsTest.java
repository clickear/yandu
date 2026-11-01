package com.yandu.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.imports.infrastructure.mysql.ImportMapper;
import com.yandu.imports.ImportApplicationService;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.translation.infrastructure.mysql.MachineTranslationMapper;
import com.yandu.translation.infrastructure.mysql.TranslationMapper;
import com.yandu.workspace.infrastructure.mysql.MyBatisProjectAccessPolicy;
import com.yandu.workspace.infrastructure.mysql.WorkspaceMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.WebSocket;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Runs the real Flyway chain against the MySQL 8.0 service used by CI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.audiences=yandu-api",
        "yandu.import.poll-ms=86400000",
        "yandu.sync.netty.enabled=true",
        "yandu.sync.netty.port=19091",
        "yandu.sync.websocket.enabled=true"
})
@EnabledIfSystemProperty(named = "yandu.mysql.integration", matches = "true")
class MySql8MigrationsTest {

    @Autowired private Flyway flyway;
    @Autowired private DataSource dataSource;
    @Autowired private ImportMapper imports;
    @Autowired private MachineTranslationMapper machineTranslations;
    @Autowired private TranslationMapper translations;
    @Autowired private WorkspaceMapper workspace;
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper json;
    @Autowired private ReleaseApplicationService releases;
    @Autowired private ImportApplicationService importJobs;
    @MockitoBean private JwtDecoder jwtDecoder;
    @LocalServerPort private int port;

    @Test
    void migratesTheCurrentSchemaAndRetainsOperationalMetadata() throws Exception {
        assertNotNull(flyway.info().current());
        assertEquals("11", flyway.info().current().getVersion().getVersion());
        // Execute the migrations' newer read/write SQL through MyBatis, not a test database dialect.
        assertTrue(imports.findRecent(Long.MAX_VALUE, 20).isEmpty());
        assertEquals(0, imports.claim(Long.MAX_VALUE, java.time.Instant.now()));
        assertTrue(machineTranslations.findJobs(Long.MAX_VALUE, 20).isEmpty());
        assertTrue(translations.revisionHistory(Long.MAX_VALUE, Long.MAX_VALUE, "en-US", 100).isEmpty());
        assertTrue(workspace.findArchivedTargetLocales(Long.MAX_VALUE).isEmpty());

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("""
                    SELECT column_comment
                      FROM information_schema.columns
                     WHERE table_schema = DATABASE()
                       AND table_name = 'import_jobs'
                       AND column_name = 'run_generation'
                    """)) {
                assertTrue(result.next());
                assertEquals("导入 worker 租约代次；每次领取递增，迟到 worker 不能修改后续尝试",
                        result.getString(1));
            }
            try (var result = statement.executeQuery("""
                    SELECT COUNT(*)
                      FROM information_schema.statistics
                     WHERE table_schema = DATABASE()
                       AND table_name = 'import_jobs'
                       AND index_name = 'ix_import_jobs_project_created'
                    """)) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
            }
        }
    }

    @Test
    void archivedProjectMembersCanReadButCannotWrite() throws Exception {
        long actorId = 910_000_000_000_001L;
        long projectId = 910_000_000_000_002L;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO external_identities (user_id, issuer, subject, created_at)
                    VALUES (910000000000001, 'https://issuer.example.test', 'archived-viewer', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO projects (id, owner_id, name, source_locale, version, status, created_at, updated_at)
                    VALUES (910000000000002, 910000000000001, 'Archived access check', 'zh-CN', 1,
                            'ARCHIVED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_members (project_id, user_id, role, created_at)
                    VALUES (910000000000002, 910000000000001, 'VIEWER', UTC_TIMESTAMP(6))
                    """);
        }

        var policy = new MyBatisProjectAccessPolicy(workspace);
        policy.requireCanReadCatalog(actorId, projectId);
        policy.requireCanReadTranslation(actorId, projectId);
        assertThrows(AccessDeniedException.class,
                () -> policy.requireCanManageTranslation(actorId, projectId));
        assertThrows(AccessDeniedException.class,
                () -> policy.requireCanPublish(actorId, projectId));
    }

    @Test
    void consumerCanFetchPublishedSnapshotFromMysqlWithoutAuthentication() throws Exception {
        long actorId = 920_000_000_000_001L;
        long projectId = 920_000_000_000_002L;
        long releaseId = 920_000_000_000_003L;
        String content = "{\"welcome.title\":\"你好\"}\n";
        String checksum = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO external_identities (user_id, issuer, subject, created_at)
                    VALUES (920000000000001, 'https://issuer.example.test', 'translation-consumer', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO projects (id, owner_id, name, source_locale, version, status, created_at, updated_at)
                    VALUES (920000000000002, 920000000000001, 'Consumer sync check', 'zh-CN', 1, 'ACTIVE',
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_languages (project_id, locale, created_at)
                    VALUES (920000000000002, 'en-US', UTC_TIMESTAMP(6))
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO releases
                      (id, project_id, locale, format, version_no, content, manifest_json,
                       checksum_sha256, published_by, published_at)
                    VALUES (?, ?, 'en-US', 'json-flat-v1', 1, ?, '[]', ?, ?, UTC_TIMESTAMP(6))
                    """)) {
                insert.setLong(1, releaseId);
                insert.setLong(2, projectId);
                insert.setString(3, content);
                insert.setString(4, checksum);
                insert.setLong(5, actorId);
                insert.executeUpdate();
            }
        }

        String baseUrl = "http://localhost:" + port + "/api/v1/public/projects/" + projectId
                + "/translations/en-US/latest";
        ResponseEntity<JsonNode> metadata = http.getForEntity(baseUrl, JsonNode.class);
        assertEquals(HttpStatus.OK, metadata.getStatusCode());
        assertEquals(Long.toString(releaseId), metadata.getBody().path("id").asText());
        assertEquals(checksum, metadata.getBody().path("checksumSha256").asText());

        ResponseEntity<String> snapshot = http.getForEntity(baseUrl + "/content", String.class);
        assertEquals(HttpStatus.OK, snapshot.getStatusCode());
        assertEquals(content, snapshot.getBody());
        assertEquals(checksum, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(snapshot.getBody().getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void sourceJsonImportPreviewIsIdempotentAndAppliesThroughMysqlWorker() throws Exception {
        long projectId = 940_000_000_000_002L;
        long namespaceId = 940_000_000_000_003L;
        String subject = "import-owner";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO external_identities (user_id, issuer, subject, created_at)
                    VALUES (940000000000001, 'https://issuer.example.test', 'import-owner', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO projects (id, owner_id, name, source_locale, version, status, created_at, updated_at)
                    VALUES (940000000000002, 940000000000001, 'JSON import check', 'zh-CN', 1, 'ACTIVE',
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_languages (project_id, locale, created_at)
                    VALUES (940000000000002, 'en-US', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_members (project_id, user_id, role, created_at)
                    VALUES (940000000000002, 940000000000001, 'OWNER', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO namespaces (id, project_id, name, status, created_by, created_at)
                    VALUES (940000000000003, 940000000000002, 'default', 'ACTIVE',
                            940000000000001, UTC_TIMESTAMP(6))
                    """);
        }

        HttpHeaders headers = authenticatedHeaders("mysql-import-integration-token", subject);
        headers.set("Idempotency-Key", "source-import-940");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String previewEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/imports/previews";
        String payload = "{\"home.title\":\"欢迎\",\"home.subtitle\":\"请登录\"}";
        String body = json.writeValueAsString(java.util.Map.of(
                "namespaceId", namespaceId, "locale", "zh-CN", "json", payload));
        var previewRequest = new HttpEntity<>(body, headers);
        ResponseEntity<JsonNode> preview = http.postForEntity(previewEndpoint, previewRequest, JsonNode.class);
        ResponseEntity<JsonNode> replay = http.postForEntity(previewEndpoint, previewRequest, JsonNode.class);
        assertEquals(HttpStatus.OK, preview.getStatusCode());
        assertEquals("PREVIEW_READY", preview.getBody().path("status").asText());
        assertEquals(2, preview.getBody().path("preview").path("added").asInt());
        String jobId = preview.getBody().path("id").asText();
        assertEquals(jobId, replay.getBody().path("id").asText(),
                "same project key and payload must reuse the saved preview");

        String changedBody = json.writeValueAsString(java.util.Map.of(
                "namespaceId", namespaceId, "locale", "zh-CN", "json", "{\"home.title\":\"别的内容\"}"));
        ResponseEntity<JsonNode> conflict = http.postForEntity(previewEndpoint,
                new HttpEntity<>(changedBody, headers), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        String applyEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/imports/" + jobId + "/apply";
        String applyBody = json.writeValueAsString(java.util.Map.of("previewHash",
                preview.getBody().path("previewHash").asText()));
        ResponseEntity<JsonNode> queued = http.postForEntity(applyEndpoint,
                new HttpEntity<>(applyBody, headers), JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, queued.getStatusCode());
        assertEquals("QUEUED", queued.getBody().path("status").asText());

        String namespaceEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/namespaces/" + namespaceId;
        ResponseEntity<Void> archived = http.exchange(namespaceEndpoint, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.OK, archived.getStatusCode());
        importJobs.dispatchQueuedJobs();
        String jobEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/imports/" + jobId;
        JsonNode failed = awaitImportStatus(jobEndpoint, headers, "FAILED");
        assertFalse(failed.path("error").asText().isBlank());

        ResponseEntity<JsonNode> restored = http.postForEntity(namespaceEndpoint + "/restore",
                new HttpEntity<>(headers), JsonNode.class);
        assertEquals(HttpStatus.OK, restored.getStatusCode());
        ResponseEntity<JsonNode> retried = http.postForEntity(jobEndpoint + "/retry",
                new HttpEntity<>(applyBody, headers), JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, retried.getStatusCode());
        assertEquals("QUEUED", retried.getBody().path("status").asText());
        importJobs.dispatchQueuedJobs();
        JsonNode completed = awaitImportStatus(jobEndpoint, headers, "COMPLETED");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT entry_key, source_text, source_revision
                       FROM catalog_entries
                      WHERE project_id = ? AND namespace_id = ? AND status = 'ACTIVE'
                      ORDER BY entry_key
                     """)) {
            statement.setLong(1, projectId);
            statement.setLong(2, namespaceId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("home.subtitle", rows.getString("entry_key"));
                assertEquals("请登录", rows.getString("source_text"));
                assertEquals(1, rows.getLong("source_revision"));
                assertTrue(rows.next());
                assertEquals("home.title", rows.getString("entry_key"));
                assertEquals("欢迎", rows.getString("source_text"));
                assertEquals(1, rows.getLong("source_revision"));
                assertFalse(rows.next(), "idempotent replay must not create duplicate catalog entries");
            }
        }
        assertEquals("COMPLETED", completed.path("status").asText());

        String targetLocaleBody = json.writeValueAsString(java.util.Map.of(
                "namespaceId", namespaceId, "locale", "en-US", "json", "{\"unknown.key\":\"No source\"}"));
        headers.set("Idempotency-Key", "unknown-target-import-940");
        ResponseEntity<JsonNode> unknownTargetPreview = http.postForEntity(previewEndpoint,
                new HttpEntity<>(targetLocaleBody, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, unknownTargetPreview.getStatusCode());
        assertEquals(1, unknownTargetPreview.getBody().path("preview").path("conflicts").asInt());
        String unknownApplyEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/imports/" + unknownTargetPreview.getBody().path("id").asText() + "/apply";
        String unknownApplyBody = json.writeValueAsString(java.util.Map.of("previewHash",
                unknownTargetPreview.getBody().path("previewHash").asText()));
        ResponseEntity<JsonNode> unknownApply = http.postForEntity(unknownApplyEndpoint,
                new HttpEntity<>(unknownApplyBody, headers), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, unknownApply.getStatusCode(),
                "target-language import must not create translations without a source entry");

        headers.set("Idempotency-Key", "target-import-940");
        String existingTargetBody = json.writeValueAsString(java.util.Map.of(
                "namespaceId", namespaceId, "locale", "en-US", "json", "{\"home.title\":\"Welcome\"}"));
        ResponseEntity<JsonNode> targetPreview = http.postForEntity(previewEndpoint,
                new HttpEntity<>(existingTargetBody, headers), JsonNode.class);
        assertEquals(HttpStatus.OK, targetPreview.getStatusCode());
        assertEquals(0, targetPreview.getBody().path("preview").path("conflicts").asInt());
        assertEquals("ADD", targetPreview.getBody().path("preview").path("items").get(0).path("action").asText());
        String targetJobId = targetPreview.getBody().path("id").asText();
        String targetJobEndpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId
                + "/imports/" + targetJobId;
        String targetApplyBody = json.writeValueAsString(java.util.Map.of("previewHash",
                targetPreview.getBody().path("previewHash").asText()));
        ResponseEntity<JsonNode> targetQueued = http.postForEntity(targetJobEndpoint + "/apply",
                new HttpEntity<>(targetApplyBody, headers), JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, targetQueued.getStatusCode());
        importJobs.dispatchQueuedJobs();
        assertEquals("COMPLETED", awaitImportStatus(targetJobEndpoint, headers, "COMPLETED")
                .path("status").asText());

        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT t.value, t.status, t.based_on_source_revision, t.version
                  FROM translations t
                  JOIN catalog_entries e ON e.project_id = t.project_id AND e.id = t.entry_id
                 WHERE t.project_id = ? AND e.entry_key = 'home.title' AND t.target_locale = 'en-US'
                """)) {
            statement.setLong(1, projectId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("Welcome", rows.getString("value"));
                assertEquals("DRAFT", rows.getString("status"));
                assertEquals(1, rows.getLong("based_on_source_revision"));
                assertEquals(1, rows.getLong("version"));
                assertFalse(rows.next());
            }
        }
    }

    private JsonNode awaitImportStatus(String jobEndpoint, HttpHeaders headers, String expectedStatus)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            ResponseEntity<JsonNode> current = http.exchange(jobEndpoint, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class);
            assertEquals(HttpStatus.OK, current.getStatusCode());
            String status = current.getBody().path("status").asText();
            if (expectedStatus.equals(status)) return current.getBody();
            if ("FAILED".equals(status) || "COMPLETED".equals(status)) {
                throw new AssertionError("Expected import status " + expectedStatus + " but got " + current.getBody());
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Import job did not reach " + expectedStatus + " before the integration deadline");
    }

    @Test
    @EnabledIfSystemProperty(named = "yandu.sync.integration", matches = "true")
    void completedMysqlReleaseJobsPushToNettyAndWebSocketConsumers() throws Exception {
        long actorId = 930_000_000_000_001L;
        long projectId = 930_000_000_000_002L;
        String initialContent = "{\"welcome.title\":\"Bonjour\"}\n";
        String latestContent = "{\"welcome.title\":\"Bienvenue\"}\n";

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO external_identities (user_id, issuer, subject, created_at)
                    VALUES (930000000000001, 'https://issuer.example.test', 'sync-consumer', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO projects (id, owner_id, name, source_locale, version, status, created_at, updated_at)
                    VALUES (930000000000002, 930000000000001, 'Realtime sync check', 'en-US', 1, 'ACTIVE',
                            UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_languages (project_id, locale, created_at)
                    VALUES (930000000000002, 'fr-FR', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO project_members (project_id, user_id, role, created_at)
                    VALUES (930000000000002, 930000000000001, 'OWNER', UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO namespaces (id, project_id, name, status, created_by, created_at)
                    VALUES (930000000000003, 930000000000002, 'default', 'ACTIVE',
                            930000000000001, UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO catalog_entries
                      (id, project_id, namespace_id, source_locale, entry_key, source_text, source_revision,
                       version, status, created_by, updated_by, created_at, updated_at)
                    VALUES (930000000000004, 930000000000002, 930000000000003, 'en-US',
                            'welcome.title', 'Welcome', 1, 1, 'ACTIVE', 930000000000001,
                            930000000000001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO translations
                      (id, project_id, entry_id, target_locale, value, based_on_source_revision,
                       status, version, translated_by, updated_by, created_at, updated_at)
                    VALUES (930000000000005, 930000000000002, 930000000000004, 'fr-FR',
                            'Bonjour', 1, 'APPROVED', 1, 930000000000001,
                            930000000000001, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO translation_revisions
                      (id, project_id, translation_id, entry_id, target_locale, translation_version,
                       value, based_on_source_revision, edited_by, created_at)
                    VALUES (930000000000006, 930000000000002, 930000000000005, 930000000000004,
                            'fr-FR', 1, 'Bonjour', 1, 930000000000001, UTC_TIMESTAMP(6))
                    """);
        }

        var firstJob = publishJobAndAwait(projectId, "sync-initial");
        String initialReleaseId = firstJob.releaseId();
        String initialChecksum = latestChecksum(projectId, "fr-FR");
        String endpoint = "http://localhost:" + port + "/api/v1/public/projects/" + projectId
                + "/translations/fr-FR/latest";
        ResponseEntity<JsonNode> initialMetadata = http.getForEntity(endpoint, JsonNode.class);
        assertEquals(HttpStatus.OK, initialMetadata.getStatusCode());
        assertEquals(initialReleaseId, initialMetadata.getBody().path("id").asText());
        assertEquals(initialChecksum, initialMetadata.getBody().path("checksumSha256").asText());
        assertEquals(initialContent, http.getForEntity(endpoint + "/content", String.class).getBody());

        try (var netty = new Socket()) {
            netty.connect(new InetSocketAddress("127.0.0.1", 19091), 3_000);
            netty.setSoTimeout(5_000);
            var nettyReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(netty.getInputStream(), StandardCharsets.UTF_8));
            var nettyWriter = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(netty.getOutputStream(), StandardCharsets.UTF_8));
            nettyWriter.write(subscription(projectId));
            nettyWriter.newLine();
            nettyWriter.flush();
            assertEquals(initialChecksum, notificationChecksum(json.readTree(nettyReader.readLine())));

            var websocketMessages = new LinkedBlockingQueue<String>();
            WebSocket websocket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/public/translations/ws"),
                            new WebSocket.Listener() {
                                @Override
                                public void onOpen(WebSocket socket) {
                                    socket.request(1);
                                    socket.sendText(subscription(projectId).strip(), true);
                                }

                                @Override
                                public CompletableFuture<?> onText(WebSocket socket, CharSequence data,
                                                                   boolean last) {
                                    if (last) websocketMessages.offer(data.toString());
                                    socket.request(1);
                                    return CompletableFuture.completedFuture(null);
                                }
                            }).join();
            assertEquals(initialChecksum, notificationChecksum(json.readTree(
                    websocketMessages.poll(5, TimeUnit.SECONDS))));

            try (var connection = dataSource.getConnection(); var update = connection.prepareStatement("""
                    UPDATE translations
                       SET value = 'Bienvenue', version = 2, updated_by = ?, updated_at = UTC_TIMESTAMP(6)
                     WHERE project_id = ? AND entry_id = ? AND target_locale = 'fr-FR'
                    """)) {
                update.setLong(1, actorId);
                update.setLong(2, projectId);
                update.setLong(3, 930_000_000_000_004L);
                assertEquals(1, update.executeUpdate());
            }
            try (var connection = dataSource.getConnection(); var insert = connection.prepareStatement("""
                    INSERT INTO translation_revisions
                      (id, project_id, translation_id, entry_id, target_locale, translation_version,
                       value, based_on_source_revision, edited_by, created_at)
                    VALUES (930000000000007, 930000000000002, 930000000000005, 930000000000004,
                            'fr-FR', 2, 'Bienvenue', 1, 930000000000001, UTC_TIMESTAMP(6))
                    """)) {
                assertEquals(1, insert.executeUpdate());
            }
            var latestJob = publishJobAndAwait(projectId, "sync-updated");
            String latestReleaseId = latestJob.releaseId();
            String latestChecksum = latestChecksum(projectId, "fr-FR");

            assertEquals(latestChecksum, notificationChecksum(json.readTree(nettyReader.readLine())));
            assertEquals(latestChecksum, notificationChecksum(json.readTree(
                    websocketMessages.poll(5, TimeUnit.SECONDS))));

            ResponseEntity<JsonNode> metadata = http.getForEntity(endpoint, JsonNode.class);
            assertEquals(latestReleaseId, metadata.getBody().path("id").asText());
            assertEquals(latestChecksum, metadata.getBody().path("checksumSha256").asText());
            ResponseEntity<String> snapshot = http.getForEntity(endpoint + "/content", String.class);
            assertEquals(HttpStatus.OK, snapshot.getStatusCode());
            assertEquals(latestContent, snapshot.getBody());
            assertEquals(latestChecksum, checksum(snapshot.getBody()));
            websocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    private ReleaseApplicationService.ReleaseJobView publishJobAndAwait(long projectId, String idempotencyKey)
            throws InterruptedException {
        // A single verified test principal exercises the protected HTTP boundary without configuring a real IdP.
        String token = "mysql-release-integration-token";
        String endpoint = "http://localhost:" + port + "/api/v1/projects/" + projectId + "/releases";
        HttpHeaders requestHeaders = authenticatedHeaders(token, "sync-consumer");
        requestHeaders.set("Idempotency-Key", idempotencyKey);
        requestHeaders.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>("{\"locale\":\"fr-FR\",\"format\":\"json-flat-v1\"}", requestHeaders);
        ResponseEntity<JsonNode> created = http.exchange(endpoint, HttpMethod.POST, request, JsonNode.class);
        ResponseEntity<JsonNode> duplicate = http.exchange(endpoint, HttpMethod.POST, request, JsonNode.class);
        assertEquals(HttpStatus.ACCEPTED, created.getStatusCode());
        String jobId = created.getBody().path("id").asText();
        assertEquals(jobId, duplicate.getBody().path("id").asText(),
                "repeating the HTTP publish request must reuse its job");
        releases.dispatchQueuedJobs();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            HttpHeaders pollHeaders = new HttpHeaders();
            pollHeaders.setBearerAuth(token);
            ResponseEntity<JsonNode> current = http.exchange("http://localhost:" + port + "/api/v1/projects/"
                            + projectId + "/releases/jobs/" + jobId,
                    HttpMethod.GET, new HttpEntity<>(pollHeaders), JsonNode.class);
            assertEquals(HttpStatus.OK, current.getStatusCode());
            String status = current.getBody().path("status").asText();
            if ("COMPLETED".equals(status)) return json.convertValue(current.getBody(),
                    ReleaseApplicationService.ReleaseJobView.class);
            if ("FAILED".equals(status)) throw new AssertionError("Release job failed: " + current.getBody());
            Thread.sleep(25);
        }
        throw new AssertionError("Release job did not complete before the integration-test deadline");
    }

    private HttpHeaders authenticatedHeaders(String token, String subject) {
        var now = java.time.Instant.now();
        given(jwtDecoder.decode(token)).willReturn(Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .issuer("https://issuer.example.test")
                .subject(subject)
                .audience(java.util.List.of("yandu-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String latestChecksum(long projectId, String locale) {
        var metadata = releases.latestPublished(projectId, locale, ReleaseApplicationService.JSON_FLAT);
        return metadata.checksumSha256();
    }

    private String subscription(long projectId) {
        return "{\"type\":\"subscribe\",\"projectId\":" + projectId
                + ",\"locale\":\"fr-FR\",\"format\":\"json-flat-v1\"}";
    }

    private static String notificationChecksum(JsonNode message) {
        assertEquals("release-changed", message.path("type").asText());
        return message.path("metadata").path("checksumSha256").asText();
    }

    private static String checksum(String content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
