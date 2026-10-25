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
}
