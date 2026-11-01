package com.yandu.imports.infrastructure.mysql;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ImportMapper extends BaseMapper<ImportMapper.ImportJobRow> {
    @Select("SELECT * FROM project_migrations WHERE id = #{id}")
    ProjectMigrationRow findMigration(@Param("id") long id);

    @Select("SELECT * FROM project_migrations WHERE id = #{id} FOR UPDATE")
    ProjectMigrationRow findMigrationForUpdate(@Param("id") long id);

    @Select("SELECT * FROM project_migrations WHERE actor_id = #{actorId} AND source_project_id = #{sourceProjectId}")
    ProjectMigrationRow findMigrationBySource(@Param("actorId") long actorId,
                                               @Param("sourceProjectId") String sourceProjectId);

    @Select("SELECT * FROM project_migrations WHERE status = 'QUEUED' ORDER BY updated_at, id LIMIT #{limit}")
    List<ProjectMigrationRow> findQueuedMigrations(@Param("limit") int limit);

    @Update("UPDATE project_migrations SET status = 'RUNNING', updated_at = #{updatedAt} WHERE id = #{id} AND status = 'QUEUED'")
    int claimMigration(@Param("id") long id, @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO project_migrations
              (id, actor_id, source_project_id, package_json, package_hash, target_name,
               preview_json, preview_hash, status, target_project_id, completed_entries,
               entry_id_map_json, version, created_at, updated_at, error)
            VALUES (#{id}, #{actorId}, #{sourceProjectId}, #{packageJson}, #{packageHash}, #{targetName},
                    #{previewJson}, #{previewHash}, #{status}, #{targetProjectId}, #{completedEntries},
                    #{entryIdMapJson}, #{version}, #{createdAt}, #{updatedAt}, #{error})
            """)
    int insertMigration(ProjectMigrationRow row);

    @Update("""
            UPDATE project_migrations SET target_name = #{targetName}, preview_json = #{previewJson},
              preview_hash = #{previewHash}, status = #{status}, target_project_id = #{targetProjectId},
              completed_entries = #{completedEntries}, entry_id_map_json = #{entryIdMapJson},
              version = #{version}, updated_at = #{updatedAt}, error = #{error}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int saveMigration(ProjectMigrationRow row, @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE project_migrations SET status = 'QUEUED', error = NULL, updated_at = #{updatedAt}
             WHERE status = 'RUNNING' AND updated_at < #{runningBefore}
            """)
    int recoverStaleMigrations(@Param("runningBefore") Instant runningBefore,
                               @Param("updatedAt") Instant updatedAt);

    @Select("SELECT * FROM import_jobs WHERE project_id = #{projectId} AND id = #{jobId}")
    ImportJobRow find(@Param("projectId") long projectId, @Param("jobId") long jobId);

    @Select("SELECT * FROM import_jobs WHERE project_id = #{projectId} AND idempotency_key = #{key}")
    ImportJobRow findByKey(@Param("projectId") long projectId, @Param("key") String key);

    @Select("SELECT * FROM import_jobs WHERE status = 'QUEUED' ORDER BY created_at, id LIMIT #{limit}")
    List<ImportJobRow> findQueued(@Param("limit") int limit);

    @Update("""
            UPDATE import_jobs SET status = 'QUEUED', error = 'Worker lease expired; retrying', updated_at = #{updatedAt}
             WHERE status = 'RUNNING' AND updated_at < #{runningBefore}
            """)
    int recoverStale(@Param("runningBefore") Instant runningBefore,
                     @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO import_jobs
                (id, project_id, namespace_id, locale, project_version, idempotency_key,
                 payload_json, preview_json, preview_hash, status, created_by, created_at, updated_at)
            VALUES (#{id}, #{projectId}, #{namespaceId}, #{locale}, #{projectVersion}, #{idempotencyKey},
                    #{payloadJson}, #{previewJson}, #{previewHash}, #{status}, #{createdBy}, #{createdAt}, #{updatedAt})
            """)
    int insertJob(ImportJobRow row);

    @Update("""
            UPDATE import_jobs SET status = 'QUEUED', error = NULL, updated_at = #{updatedAt}
             WHERE project_id = #{projectId} AND id = #{jobId} AND preview_hash = #{previewHash}
               AND status IN ('PREVIEW_READY', 'FAILED')
            """)
    int queue(@Param("projectId") long projectId, @Param("jobId") long jobId,
              @Param("previewHash") String previewHash, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE import_jobs SET status = 'RUNNING', updated_at = #{updatedAt} WHERE id = #{jobId} AND status = 'QUEUED'")
    int claim(@Param("jobId") long jobId, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE import_jobs SET status = 'COMPLETED', error = NULL, updated_at = #{updatedAt} WHERE id = #{jobId} AND status = 'RUNNING'")
    int complete(@Param("jobId") long jobId, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE import_jobs SET status = 'FAILED', error = #{error}, updated_at = #{updatedAt} WHERE id = #{jobId} AND status = 'RUNNING'")
    int fail(@Param("jobId") long jobId, @Param("error") String error,
             @Param("updatedAt") Instant updatedAt);

    @TableName("import_jobs")
    @Getter
    @Setter
    @NoArgsConstructor
    class ImportJobRow {
        private Long id;
        private Long projectId;
        private Long namespaceId;
        private String locale;
        private Long projectVersion;
        private String idempotencyKey;
        private String payloadJson;
        private String previewJson;
        private String previewHash;
        private String status;
        private Long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private String error;
    }

    @TableName("project_migrations")
    @Getter @Setter @NoArgsConstructor
    class ProjectMigrationRow {
        private Long id;
        private Long actorId;
        private String sourceProjectId;
        private String packageJson;
        private String packageHash;
        private String targetName;
        private String previewJson;
        private String previewHash;
        private String status;
        private Long targetProjectId;
        private Integer completedEntries;
        private String entryIdMapJson;
        private Long version;
        private Instant createdAt;
        private Instant updatedAt;
        private String error;
    }
}
