package com.yandu.translation.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Mapper
public interface MachineTranslationMapper extends BaseMapper<MachineTranslationMapper.JobRow> {

    @Select("SELECT * FROM machine_translation_jobs WHERE project_id = #{projectId} "
            + "AND created_by = #{actorId} AND idempotency_key = #{key}")
    JobRow findByIdempotencyKey(@Param("projectId") long projectId,
                                @Param("actorId") long actorId,
                                @Param("key") String idempotencyKey);

    @Select("SELECT * FROM machine_translation_jobs WHERE project_id = #{projectId} "
            + "AND created_by = #{actorId} AND idempotency_key = #{key} FOR UPDATE")
    JobRow findByIdempotencyKeyForUpdate(@Param("projectId") long projectId,
                                         @Param("actorId") long actorId,
                                         @Param("key") String idempotencyKey);

    @Select("SELECT project_id FROM project_languages WHERE project_id = #{projectId} "
            + "AND locale = #{locale} AND status = 'ACTIVE' FOR UPDATE")
    Long lockActiveTargetLocale(@Param("projectId") long projectId, @Param("locale") String locale);

    @Insert("""
            INSERT IGNORE INTO machine_translation_jobs
                (id, project_id, source_locale, target_locale, idempotency_key, request_hash,
                 status, total_count, completed_count, skipped_count, failed_count,
                 created_by, created_at, updated_at)
            VALUES (#{id}, #{projectId}, #{sourceLocale}, #{targetLocale}, #{idempotencyKey},
                    #{requestHash}, 'QUEUED', 0, 0, 0, 0, #{actorId}, #{createdAt}, #{createdAt})
            """)
    int insertJobIfAbsent(@Param("id") long id, @Param("projectId") long projectId,
                          @Param("sourceLocale") String sourceLocale,
                          @Param("targetLocale") String targetLocale,
                          @Param("idempotencyKey") String idempotencyKey,
                          @Param("requestHash") String requestHash,
                          @Param("actorId") long actorId, @Param("createdAt") Instant createdAt);

    @Insert("""
            <script>
            INSERT INTO machine_translation_job_items
                (job_id, project_id, entry_id, entry_key, source_text, source_revision,
                 expected_translation_version, status, attempts, created_at, updated_at)
            SELECT #{jobId}, e.project_id, e.id, e.entry_key, e.source_text, e.source_revision,
                   t.version, 'QUEUED', 0, #{createdAt}, #{createdAt}
              FROM catalog_entries e
              JOIN project_languages l ON l.project_id = e.project_id
                   AND l.locale = #{targetLocale} AND l.status = 'ACTIVE'
              LEFT JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
                   AND t.target_locale = #{targetLocale}
             WHERE e.project_id = #{projectId} AND e.status = 'ACTIVE'
               AND (t.id IS NULL OR (t.value = '' AND t.status <> 'APPROVED'))
               <if test='entryIds != null and !entryIds.isEmpty()'>
               AND e.id IN
                 <foreach collection='entryIds' item='entryId' open='(' separator=',' close=')'>#{entryId}</foreach>
               </if>
             ORDER BY e.id
            </script>
            """)
    int insertEligibleItems(@Param("jobId") long jobId, @Param("projectId") long projectId,
                            @Param("targetLocale") String targetLocale,
                            @Param("entryIds") List<Long> entryIds, @Param("createdAt") Instant createdAt);

    @Update("UPDATE machine_translation_jobs SET total_count = "
            + "(SELECT COUNT(*) FROM machine_translation_job_items WHERE job_id = #{jobId}), updated_at = #{updatedAt} "
            + "WHERE id = #{jobId}")
    int setInitialTotal(@Param("jobId") long jobId, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE machine_translation_jobs SET status = 'COMPLETED', updated_at = #{updatedAt} "
            + "WHERE id = #{jobId} AND total_count = 0")
    int completeEmptyJob(@Param("jobId") long jobId, @Param("updatedAt") Instant updatedAt);

    @Select("SELECT * FROM machine_translation_jobs WHERE project_id = #{projectId} AND id = #{jobId}")
    JobRow findJob(@Param("projectId") long projectId, @Param("jobId") long jobId);

    @Select("SELECT * FROM machine_translation_jobs WHERE project_id = #{projectId} "
            + "ORDER BY created_at DESC, id DESC LIMIT #{limit}")
    List<JobRow> findJobs(@Param("projectId") long projectId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT COUNT(*) FROM catalog_entries WHERE project_id = #{projectId} AND status = 'ACTIVE'
              AND id IN <foreach collection='entryIds' item='entryId' open='(' separator=',' close=')'>#{entryId}</foreach>
            </script>
            """)
    long countActiveEntries(@Param("projectId") long projectId, @Param("entryIds") List<Long> entryIds);

    @Select("""
            SELECT i.job_id, i.project_id, j.source_locale, j.target_locale, i.entry_id,
                   i.entry_key, i.source_text, i.source_revision, i.expected_translation_version,
                   j.created_by AS actor_id, i.attempts, i.status, i.error
              FROM machine_translation_job_items i
              JOIN machine_translation_jobs j ON j.project_id = i.project_id AND j.id = i.job_id
             WHERE i.project_id = #{projectId} AND i.job_id = #{jobId}
             ORDER BY i.entry_id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ItemRow> findItems(@Param("projectId") long projectId, @Param("jobId") long jobId,
                            @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM machine_translation_job_items WHERE project_id = #{projectId} AND job_id = #{jobId}")
    long countItems(@Param("projectId") long projectId, @Param("jobId") long jobId);

    @Select("SELECT id FROM machine_translation_jobs WHERE status IN ('QUEUED', 'RUNNING') ORDER BY id")
    List<Long> findUnfinishedJobIds();

    @Update("""
            UPDATE machine_translation_job_items
               SET status = 'QUEUED', error = NULL, updated_at = #{updatedAt}
             WHERE project_id = #{projectId} AND job_id = #{jobId} AND status = 'FAILED'
            """)
    int retryFailed(@Param("projectId") long projectId, @Param("jobId") long jobId,
                    @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE machine_translation_job_items
               SET status = CASE WHEN attempts < 3 THEN 'QUEUED' ELSE 'FAILED' END,
                   error = CASE WHEN attempts < 3 THEN NULL ELSE '任务工作进程中断，重试次数已用尽。' END,
                   updated_at = #{updatedAt}
             WHERE status = 'RUNNING' AND updated_at < #{staleBefore}
            """)
    int recoverStaleItems(@Param("staleBefore") Instant staleBefore,
                          @Param("updatedAt") Instant updatedAt);

    @Select("""
            SELECT i.job_id, i.project_id, j.source_locale, j.target_locale, i.entry_id,
                   i.entry_key, i.source_text, i.source_revision, i.expected_translation_version,
                   j.created_by AS actor_id, i.attempts, i.status, i.error
              FROM machine_translation_job_items i
              JOIN machine_translation_jobs j ON j.project_id = i.project_id AND j.id = i.job_id
             WHERE i.status = 'QUEUED' AND j.status IN ('QUEUED', 'RUNNING')
             ORDER BY j.created_at, j.id, i.entry_id LIMIT 1
            """)
    ItemRow findNextQueuedItem();

    @Update("""
            UPDATE machine_translation_job_items
               SET status = 'RUNNING', attempts = attempts + 1, updated_at = #{updatedAt}
             WHERE entry_id = #{entryId} AND job_id = #{jobId} AND status = 'QUEUED'
            """)
    int claimItem(@Param("jobId") long jobId, @Param("entryId") long entryId,
                  @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE machine_translation_job_items SET status = 'QUEUED', attempts = GREATEST(attempts - 1, 0), "
            + "error = NULL, updated_at = #{updatedAt} WHERE job_id = #{jobId} AND entry_id = #{entryId} AND status = 'RUNNING'")
    int releaseClaim(@Param("jobId") long jobId, @Param("entryId") long entryId,
                     @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE machine_translation_job_items
               SET status = #{status}, error = #{message}, updated_at = #{updatedAt}
             WHERE entry_id = #{entryId} AND job_id = #{jobId} AND status = 'RUNNING'
            """)
    int completeItem(@Param("jobId") long jobId, @Param("entryId") long entryId,
                     @Param("status") String status, @Param("message") String message,
                     @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE machine_translation_jobs j
               SET j.completed_count = (SELECT COUNT(*) FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'COMPLETED'),
                   j.skipped_count = (SELECT COUNT(*) FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'SKIPPED'),
                   j.failed_count = (SELECT COUNT(*) FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'FAILED'),
                   j.status = CASE
                       WHEN EXISTS (SELECT 1 FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'RUNNING') THEN 'RUNNING'
                       WHEN EXISTS (SELECT 1 FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'QUEUED') THEN 'QUEUED'
                       WHEN EXISTS (SELECT 1 FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'FAILED')
                           AND EXISTS (SELECT 1 FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status IN ('COMPLETED', 'SKIPPED')) THEN 'PARTIAL'
                       WHEN EXISTS (SELECT 1 FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'FAILED') THEN 'FAILED'
                       ELSE 'COMPLETED' END,
                   j.error = (SELECT i.error FROM machine_translation_job_items i WHERE i.job_id = j.id AND i.status = 'FAILED' ORDER BY i.entry_id LIMIT 1),
                   j.updated_at = #{updatedAt}
             WHERE j.id = #{jobId}
            """)
    int refreshJob(@Param("jobId") long jobId, @Param("updatedAt") Instant updatedAt);

    @TableName("machine_translation_jobs")
    @Getter @Setter @NoArgsConstructor
    class JobRow {
        private Long id;
        private Long projectId;
        private String sourceLocale;
        private String targetLocale;
        private String idempotencyKey;
        private String requestHash;
        private String status;
        private Long totalCount;
        private Long completedCount;
        private Long skippedCount;
        private Long failedCount;
        private Long createdBy;
        private Instant createdAt;
        private Instant updatedAt;
        private String error;
    }

    @Getter @Setter @NoArgsConstructor
    class ItemRow {
        private Long jobId;
        private Long projectId;
        private String sourceLocale;
        private String targetLocale;
        private Long entryId;
        private String entryKey;
        private String sourceText;
        private Long sourceRevision;
        private Long expectedTranslationVersion;
        private Long actorId;
        private Integer attempts;
        private String status;
        private String error;
    }
}
