package com.yandu.release.infrastructure.mysql;
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

@Mapper
public interface ReleaseMapper extends BaseMapper<ReleaseMapper.ReleaseRow> {

    @Select("SELECT id FROM projects WHERE id = #{projectId} AND status = 'ACTIVE' FOR UPDATE")
    Long lockActiveProject(@Param("projectId") long projectId);

    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM releases WHERE project_id = #{projectId} AND locale = #{locale} AND format = #{format}")
    long nextVersion(@Param("projectId") long projectId, @Param("locale") String locale,
                     @Param("format") String format);

    @Insert("""
            INSERT INTO releases
                (id, project_id, locale, format, version_no, rollback_of_release_id, content, manifest_json,
                 checksum_sha256, published_by, published_at)
            VALUES (#{id}, #{projectId}, #{locale}, #{format}, #{version}, #{rollbackOfReleaseId},
                    #{content}, #{manifest},
                    #{checksum}, #{actorId}, #{createdAt})
            """)
    int insertSnapshot(@Param("id") long id, @Param("projectId") long projectId,
                       @Param("locale") String locale, @Param("format") String format,
                       @Param("version") long version,
                       @Param("rollbackOfReleaseId") Long rollbackOfReleaseId,
                       @Param("content") String content, @Param("manifest") String manifest,
                       @Param("checksum") String checksum,
                       @Param("actorId") long actorId, @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT e.id AS entry_id, e.entry_key, e.source_text, e.source_revision,
                   t.value AS translation, t.status AS translation_status,
                   t.based_on_source_revision AS translation_source_revision,
                   t.version AS translation_version
              FROM catalog_entries e
              LEFT JOIN translations t ON t.project_id = e.project_id AND t.entry_id = e.id
                   AND t.target_locale = #{locale}
             WHERE e.project_id = #{projectId} AND e.status = 'ACTIVE'
             ORDER BY e.entry_key, e.id
             FOR UPDATE
            """)
    List<ReleaseLineRow> findLines(@Param("projectId") long projectId,
                                   @Param("locale") String locale);

    @Select("""
            SELECT id, project_id, locale, format, version_no, rollback_of_release_id,
                   content, manifest_json, checksum_sha256,
                   published_by, published_at
              FROM releases
             WHERE project_id = #{projectId} AND id = #{releaseId}
            """)
    ReleaseRow findSnapshot(@Param("projectId") long projectId,
                            @Param("releaseId") long releaseId);

    @Select("""
            <script>
            SELECT id, project_id, locale, format, version_no, rollback_of_release_id,
                   content, manifest_json, checksum_sha256,
                   published_by, published_at
              FROM releases
             WHERE project_id = #{projectId}
             <if test='locale != null'>AND locale = #{locale}</if>
             <if test='format != null'>AND format = #{format}</if>
             ORDER BY version_no DESC, id DESC LIMIT #{limit}
            </script>
            """)
    List<ReleaseRow> findSnapshots(@Param("projectId") long projectId,
                                   @Param("locale") String locale,
                                   @Param("format") String format,
                                   @Param("limit") int limit);

    @Getter
    @Setter
    @NoArgsConstructor
    class ReleaseLineRow {
        private Long entryId;
        private String entryKey;
        private String sourceText;
        private Long sourceRevision;
        private String translation;
        private String translationStatus;
        private Long translationSourceRevision;
        private Long translationVersion;
    }

    @TableName("releases")
    @Getter
    @Setter
    @NoArgsConstructor
    class ReleaseRow {
        private Long id;
        private Long projectId;
        private String locale;
        private String format;
        private Long versionNo;
        private Long rollbackOfReleaseId;
        private String content;
        private String manifestJson;
        private String checksumSha256;
        private Long publishedBy;
        private Instant publishedAt;
    }
}
