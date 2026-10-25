package com.yandu.translation.infrastructure.mysql;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TranslationMapper extends BaseMapper<TranslationMapper.TranslationRow> {

    @Select("""
            SELECT t.id, t.project_id, t.entry_id, t.target_locale, t.value,
                   t.based_on_source_revision, t.status, t.version, t.translated_by,
                   t.updated_by, t.created_at, t.updated_at
              FROM translations t
              JOIN catalog_entries e ON e.project_id = t.project_id AND e.id = t.entry_id
             WHERE t.project_id = #{projectId} AND t.entry_id = #{entryId}
               AND t.target_locale = #{locale} AND e.status = 'ACTIVE'
            """)
    TranslationRow find(@Param("projectId") long projectId,
                        @Param("entryId") long entryId,
                        @Param("locale") String locale);

    @Select("""
            SELECT e.id AS entry_id, e.entry_key, e.source_text, e.source_revision,
                   t.id, t.project_id, t.target_locale, t.value, t.status, t.version,
                   t.based_on_source_revision, t.translated_by, t.updated_by,
                   t.created_at, t.updated_at
              FROM catalog_entries e
              LEFT JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
                   AND t.target_locale = #{locale}
             WHERE e.project_id = #{projectId} AND e.namespace_id = #{namespaceId}
               AND e.status = 'ACTIVE'
               AND (#{keyword} IS NULL OR e.entry_key LIKE CONCAT('%', #{keyword}, '%')
                    OR e.source_text LIKE CONCAT('%', #{keyword}, '%')
                    OR t.value LIKE CONCAT('%', #{keyword}, '%'))
               AND (#{status} = 'all'
                    OR (#{status} = 'untranslated' AND (t.id IS NULL OR t.value = ''))
                    OR (#{status} = 'draft' AND t.value <> '' AND t.status <> 'APPROVED')
                    OR (#{status} = 'reviewed' AND t.status = 'APPROVED'))
             ORDER BY e.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<TranslationRow> searchWorkspace(@Param("projectId") long projectId,
                                                   @Param("namespaceId") long namespaceId,
                                                   @Param("locale") String locale,
                                                   @Param("status") String status,
                                                   @Param("keyword") String keyword,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM catalog_entries e
              LEFT JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
                   AND t.target_locale = #{locale}
             WHERE e.project_id = #{projectId} AND e.namespace_id = #{namespaceId}
               AND e.status = 'ACTIVE'
               AND (#{keyword} IS NULL OR e.entry_key LIKE CONCAT('%', #{keyword}, '%')
                    OR e.source_text LIKE CONCAT('%', #{keyword}, '%')
                    OR t.value LIKE CONCAT('%', #{keyword}, '%'))
               AND (#{status} = 'all'
                    OR (#{status} = 'untranslated' AND (t.id IS NULL OR t.value = ''))
                    OR (#{status} = 'draft' AND t.value <> '' AND t.status <> 'APPROVED')
                    OR (#{status} = 'reviewed' AND t.status = 'APPROVED'))
            """)
    long countWorkspace(@Param("projectId") long projectId,
                        @Param("namespaceId") long namespaceId,
                        @Param("locale") String locale,
                        @Param("status") String status,
                        @Param("keyword") String keyword);

    @Select("""
            SELECT COUNT(e.id) AS total_entries,
                   COALESCE(SUM(CASE WHEN t.value IS NOT NULL AND t.value <> '' THEN 1 ELSE 0 END), 0) AS translated,
                   COALESCE(SUM(CASE WHEN t.status = 'APPROVED' AND t.value <> '' THEN 1 ELSE 0 END), 0) AS reviewed
              FROM catalog_entries e
              LEFT JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
                   AND t.target_locale = #{locale}
             WHERE e.project_id = #{projectId} AND e.namespace_id = #{namespaceId}
               AND e.status = 'ACTIVE'
            """)
    WorkspaceStatisticsRow workspaceStatistics(@Param("projectId") long projectId,
                                                @Param("namespaceId") long namespaceId,
                                                @Param("locale") String locale);

    @Select("""
            SELECT e.id AS entry_id, e.entry_key, e.source_text,
                   t.value, t.target_locale, t.version
              FROM catalog_entries e
              JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
             WHERE e.project_id = #{projectId} AND e.id <> #{excludedEntryId}
               AND e.status = 'ACTIVE' AND e.source_text = #{sourceText}
               AND t.target_locale = #{locale} AND t.status = 'APPROVED'
               AND t.based_on_source_revision = e.source_revision AND t.value <> ''
             ORDER BY t.updated_at DESC, e.id DESC
             LIMIT #{limit}
            """)
    java.util.List<TranslationRow> findApprovedMatches(@Param("projectId") long projectId,
                                                        @Param("excludedEntryId") long excludedEntryId,
                                                        @Param("locale") String locale,
                                                        @Param("sourceText") String sourceText,
                                                        @Param("limit") int limit);

    @Update("""
            UPDATE translations
               SET value = #{value}, based_on_source_revision = #{sourceRevision},
                   status = #{status}, version = #{newVersion}, translated_by = #{translatedBy},
                   updated_by = #{updatedBy}, updated_at = #{updatedAt}
             WHERE id = #{id} AND project_id = #{projectId} AND entry_id = #{entryId}
               AND target_locale = #{locale} AND version = #{expectedVersion}
            """)
    int updateOptimistically(@Param("id") long id,
                             @Param("projectId") long projectId,
                             @Param("entryId") long entryId,
                             @Param("locale") String locale,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("newVersion") long newVersion,
                             @Param("sourceRevision") long sourceRevision,
                             @Param("value") String value,
                             @Param("status") String status,
                             @Param("translatedBy") long translatedBy,
                             @Param("updatedBy") long updatedBy,
                             @Param("updatedAt") Instant updatedAt);

    @Insert("""
            INSERT INTO translation_revisions
                (id, project_id, translation_id, entry_id, target_locale, translation_version,
                 value, based_on_source_revision, edited_by, created_at)
            VALUES (#{id}, #{projectId}, #{translationId}, #{entryId}, #{locale}, #{version},
                    #{value}, #{sourceRevision}, #{actorId}, #{createdAt})
            """)
    int insertRevision(@Param("id") long id,
                       @Param("projectId") long projectId,
                       @Param("translationId") long translationId,
                       @Param("entryId") long entryId,
                       @Param("locale") String locale,
                       @Param("version") long version,
                       @Param("value") String value,
                       @Param("sourceRevision") long sourceRevision,
                       @Param("actorId") long actorId,
                       @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO translation_review_decisions
                (id, project_id, translation_id, entry_id, target_locale, decision_version,
                 source_revision, decision, reason, reviewer_id, created_at)
            VALUES (#{id}, #{projectId}, #{translationId}, #{entryId}, #{locale}, #{version},
                    #{sourceRevision}, #{decision}, #{reason}, #{reviewerId}, #{createdAt})
            """)
    int insertDecision(@Param("id") long id,
                       @Param("projectId") long projectId,
                       @Param("translationId") long translationId,
                       @Param("entryId") long entryId,
                       @Param("locale") String locale,
                       @Param("version") long version,
                       @Param("sourceRevision") long sourceRevision,
                       @Param("decision") String decision,
                       @Param("reason") String reason,
                       @Param("reviewerId") long reviewerId,
                       @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE translations
               SET status = 'NEEDS_REVIEW', version = version + 1,
                   updated_by = #{sourceEditorId}, updated_at = #{occurredAt}
             WHERE project_id = #{projectId} AND entry_id = #{entryId}
               AND based_on_source_revision < #{sourceRevision} AND status <> 'NEEDS_REVIEW'
            """)
    int markNeedsReview(@Param("projectId") long projectId,
                        @Param("entryId") long entryId,
                        @Param("sourceRevision") long sourceRevision,
                        @Param("sourceEditorId") long sourceEditorId,
                        @Param("occurredAt") Instant occurredAt);

    @TableName("translations")
    @Getter
    @Setter
    @NoArgsConstructor
    class TranslationRow {
        private Long id;
        private Long projectId;
        private Long entryId;
        private String targetLocale;
        private String value;
        private Long basedOnSourceRevision;
        private String status;
        private Long version;
        private Long translatedBy;
        private Long updatedBy;
        private Instant createdAt;
        private Instant updatedAt;
        private String entryKey;
        private String sourceText;
        private Long sourceRevision;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    class WorkspaceStatisticsRow {
        private long totalEntries;
        private long translated;
        private long reviewed;
    }
}
