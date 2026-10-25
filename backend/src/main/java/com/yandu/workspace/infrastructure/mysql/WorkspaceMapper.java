package com.yandu.workspace.infrastructure.mysql;
import com.baomidou.mybatisplus.annotation.TableName;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
public interface WorkspaceMapper extends BaseMapper<WorkspaceMapper.ProjectRow> {

    @Select("SELECT locale FROM project_languages WHERE project_id = #{projectId} ORDER BY locale")
    List<String> findTargetLocales(@Param("projectId") long projectId);

    @Select("""
            SELECT p.id, p.owner_id, p.name, p.source_locale, p.version, p.status,
                   p.created_at, p.updated_at
              FROM projects p
              JOIN project_members m ON m.project_id = p.id
             WHERE m.user_id = #{userId} AND p.status = 'ACTIVE'
             ORDER BY p.updated_at DESC, p.id DESC
            """)
    List<ProjectRow> findActiveForMember(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM external_identities WHERE user_id = #{userId}")
    long countIdentity(@Param("userId") long userId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM project_members
             WHERE project_id = #{projectId} AND user_id = #{userId}
               AND role IN
               <foreach collection='roles' item='role' open='(' separator=',' close=')'>
                   #{role}
               </foreach>
            </script>
            """)
    long countMemberRoles(@Param("projectId") long projectId,
                          @Param("userId") long userId,
                          @Param("roles") java.util.List<String> roles);

    @Insert("""
            INSERT INTO project_members (project_id, user_id, role, created_at)
            VALUES (#{projectId}, #{userId}, #{role}, #{createdAt})
            """)
    int addMember(@Param("projectId") long projectId,
                  @Param("userId") long userId,
                  @Param("role") String role,
                  @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT user_id, role, created_at
              FROM project_members WHERE project_id = #{projectId}
             ORDER BY role, user_id
            """)
    List<MemberRow> findMembers(@Param("projectId") long projectId);

    @Insert("INSERT INTO project_languages (project_id, locale, created_at) VALUES (#{projectId}, #{locale}, #{createdAt})")
    int insertTargetLocale(@Param("projectId") long projectId,
                           @Param("locale") String locale,
                           @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO namespaces (id, project_id, name, status, created_by, created_at)
            VALUES (#{id}, #{projectId}, #{name}, 'ACTIVE', #{actorId}, #{createdAt})
            """)
    int insertDefaultNamespace(@Param("id") long id,
                               @Param("projectId") long projectId,
                               @Param("name") String name,
                               @Param("actorId") long actorId,
                               @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO project_members (project_id, user_id, role, created_at)
            VALUES (#{projectId}, #{userId}, 'OWNER', #{createdAt})
            """)
    int insertOwnerMembership(@Param("projectId") long projectId,
                              @Param("userId") long userId,
                              @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE projects
               SET version = #{version}, updated_at = #{updatedAt}
             WHERE id = #{id} AND status = 'ACTIVE' AND version = #{expectedVersion}
            """)
    int updateVersion(@Param("id") long id,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("version") long version,
                      @Param("updatedAt") Instant updatedAt);

    @Getter
    @Setter
    @NoArgsConstructor
    class MemberRow {
        private Long userId;
        private String role;
        private Instant createdAt;
    }

    @TableName("projects")
    @Getter
    @Setter
    @NoArgsConstructor
    class ProjectRow {
        private Long id;
        private Long ownerId;
        private String name;
        private String sourceLocale;
        private Long version;
        private String status;
        private Instant createdAt;
        private Instant updatedAt;
    }
}
