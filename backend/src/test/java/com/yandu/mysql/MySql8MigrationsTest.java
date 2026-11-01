package com.yandu.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yandu.imports.infrastructure.mysql.ImportMapper;
import com.yandu.translation.infrastructure.mysql.MachineTranslationMapper;
import com.yandu.translation.infrastructure.mysql.TranslationMapper;
import com.yandu.workspace.infrastructure.mysql.MyBatisProjectAccessPolicy;
import com.yandu.workspace.infrastructure.mysql.WorkspaceMapper;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Runs the real Flyway chain against the MySQL 8.0 service used by CI. */
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.audiences=yandu-api"
})
@EnabledIfSystemProperty(named = "yandu.mysql.integration", matches = "true")
class MySql8MigrationsTest {

    @Autowired private Flyway flyway;
    @Autowired private DataSource dataSource;
    @Autowired private ImportMapper imports;
    @Autowired private MachineTranslationMapper machineTranslations;
    @Autowired private TranslationMapper translations;
    @Autowired private WorkspaceMapper workspace;

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
}
