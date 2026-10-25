package com.yandu.catalog.infrastructure.mysql;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SourceRevisionMapper extends BaseMapper<SourceRevisionMapper.SourceRevisionRow> {

    @Select("""
            SELECT id, project_id, entry_id, source_revision, source_text, changed_by, created_at
              FROM source_revisions
             WHERE project_id = #{projectId} AND entry_id = #{entryId}
             ORDER BY source_revision DESC
            """)
    List<SourceRevisionRow> findHistory(@Param("projectId") long projectId,
                                        @Param("entryId") long entryId);

    @TableName("source_revisions")
    @Getter
    @Setter
    @NoArgsConstructor
    class SourceRevisionRow {
        private Long id;
        private Long projectId;
        private Long entryId;
        private long sourceRevision;
        private String sourceText;
        private Long changedBy;
        private Instant createdAt;
    }
}
