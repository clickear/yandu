package com.yandu.catalog.infrastructure.mysql;
import com.baomidou.mybatisplus.annotation.TableName;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CatalogEntryMapper extends BaseMapper<CatalogEntryMapper.CatalogEntryRow> {

    @Select("""
            SELECT id, project_id, namespace_id, source_locale, entry_key,
                   source_text, source_revision, version, status, created_by, updated_by,
                   created_at, updated_at
              FROM catalog_entries
             WHERE project_id = #{projectId}
               AND id = #{entryId}
               AND status = 'ACTIVE'
             FOR UPDATE
            """)
    CatalogEntryRow findActiveForUpdate(
            @Param("projectId") long projectId,
            @Param("entryId") long entryId);

    @Select("""
            SELECT id, project_id, namespace_id, source_locale, entry_key,
                   source_text, source_revision, version, status, created_by, updated_by,
                   created_at, updated_at
              FROM catalog_entries
             WHERE project_id = #{projectId}
               AND namespace_id = #{namespaceId}
               AND status = 'ACTIVE'
               AND (#{keyword} IS NULL OR entry_key LIKE CONCAT('%', #{keyword}, '%'))
             ORDER BY id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    java.util.List<CatalogEntryRow> searchActive(
            @Param("projectId") long projectId,
            @Param("namespaceId") long namespaceId,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
              FROM catalog_entries
             WHERE project_id = #{projectId}
               AND namespace_id = #{namespaceId}
               AND status = 'ACTIVE'
               AND (#{keyword} IS NULL OR entry_key LIKE CONCAT('%', #{keyword}, '%'))
            """)
    long countActive(@Param("projectId") long projectId,
                     @Param("namespaceId") long namespaceId,
                     @Param("keyword") String keyword);

    @Update("""
            UPDATE catalog_entries
               SET status = 'ARCHIVED', updated_by = #{actorId}, updated_at = #{updatedAt}
             WHERE project_id = #{projectId} AND id = #{entryId} AND status = 'ACTIVE'
            """)
    int archive(@Param("projectId") long projectId,
                @Param("entryId") long entryId,
                @Param("actorId") long actorId,
                @Param("updatedAt") java.time.Instant updatedAt);

    @Update("""
            UPDATE catalog_entries
               SET source_text = #{sourceText},
                   source_revision = #{newRevision},
                   version = #{newVersion},
                   updated_by = #{actorId},
                   updated_at = #{updatedAt}
             WHERE project_id = #{projectId}
               AND id = #{entryId}
               AND source_revision = #{expectedRevision}
               AND version = #{expectedVersion}
               AND status = 'ACTIVE'
            """)
    int updateSourceOptimistically(
            @Param("projectId") long projectId,
            @Param("entryId") long entryId,
            @Param("expectedRevision") long expectedRevision,
            @Param("expectedVersion") long expectedVersion,
            @Param("newRevision") long newRevision,
            @Param("newVersion") long newVersion,
            @Param("sourceText") String sourceText,
            @Param("actorId") long actorId,
            @Param("updatedAt") java.time.Instant updatedAt);

    @Insert("""
            INSERT INTO glossary_terms
                (id, project_id, source_locale, target_locale, source_term, term_key,
                 target_term, note, status, created_by, created_at, updated_at)
            VALUES (#{id}, #{projectId}, #{sourceLocale}, #{targetLocale}, #{sourceTerm},
                    #{termKey}, #{targetTerm}, #{note}, 'ACTIVE', #{actorId}, #{occurredAt}, #{occurredAt})
            """)
    int insertTerm(@Param("id") long id, @Param("projectId") long projectId,
                   @Param("sourceLocale") String sourceLocale,
                   @Param("targetLocale") String targetLocale,
                   @Param("sourceTerm") String sourceTerm, @Param("termKey") String termKey,
                   @Param("targetTerm") String targetTerm, @Param("note") String note,
                   @Param("actorId") long actorId, @Param("occurredAt") Instant occurredAt);

    @Select("""
            SELECT id, project_id, source_locale, target_locale, source_term,
                   target_term, note, created_at
              FROM glossary_terms
             WHERE project_id = #{projectId} AND source_locale = #{sourceLocale}
               AND target_locale = #{targetLocale} AND status = 'ACTIVE'
             ORDER BY source_term, id LIMIT #{limit} OFFSET #{offset}
            """)
    List<GlossaryTermRow> listTerms(@Param("projectId") long projectId,
                                    @Param("sourceLocale") String sourceLocale,
                                    @Param("targetLocale") String targetLocale,
                                    @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM glossary_terms
             WHERE project_id = #{projectId} AND source_locale = #{sourceLocale}
               AND target_locale = #{targetLocale} AND status = 'ACTIVE'
            """)
    long countTerms(@Param("projectId") long projectId,
                    @Param("sourceLocale") String sourceLocale,
                    @Param("targetLocale") String targetLocale);

    @Select("""
            SELECT id, project_id, source_locale, target_locale, source_term,
                   target_term, note, created_at
              FROM glossary_terms
             WHERE project_id = #{projectId} AND source_locale = #{sourceLocale}
               AND target_locale = #{targetLocale} AND status = 'ACTIVE'
               AND LOCATE(LOWER(source_term), LOWER(#{sourceText})) > 0
             ORDER BY CHAR_LENGTH(source_term) DESC, source_term, id
             LIMIT #{limit}
            """)
    List<GlossaryTermRow> matchingTerms(@Param("projectId") long projectId,
                                        @Param("sourceLocale") String sourceLocale,
                                        @Param("targetLocale") String targetLocale,
                                        @Param("sourceText") String sourceText,
                                        @Param("limit") int limit);

    @Update("""
            UPDATE glossary_terms SET status = 'ARCHIVED', updated_at = #{occurredAt}
             WHERE project_id = #{projectId} AND id = #{termId} AND status = 'ACTIVE'
            """)
    int archiveTerm(@Param("projectId") long projectId, @Param("termId") long termId,
                    @Param("occurredAt") Instant occurredAt);

    @Getter
    @Setter
    @NoArgsConstructor
    class GlossaryTermRow {
        private Long id;
        private Long projectId;
        private String sourceLocale;
        private String targetLocale;
        private String sourceTerm;
        private String targetTerm;
        private String note;
        private Instant createdAt;
    }

    @TableName("catalog_entries")
    @Getter
    @Setter
    @NoArgsConstructor
    class CatalogEntryRow {
        private Long id;
        private Long projectId;
        private Long namespaceId;
        private String sourceLocale;
        private String entryKey;
        private String sourceText;
        private long sourceRevision;
        private long version;
        private String status;
        private Long createdBy;
        private Long updatedBy;
        private Instant createdAt;
        private Instant updatedAt;
    }
}
