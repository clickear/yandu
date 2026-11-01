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

    @Select("SELECT locale FROM project_languages WHERE project_id = #{projectId} AND status = 'ACTIVE' ORDER BY locale")
    List<String> findTargetLocales(@Param("projectId") long projectId);

    @Select("SELECT locale FROM project_languages WHERE project_id = #{projectId} AND status = 'ARCHIVED' ORDER BY locale")
    List<String> findArchivedTargetLocales(@Param("projectId") long projectId);

    @Select("SELECT COUNT(*) FROM project_languages WHERE project_id = #{projectId} AND status = 'ACTIVE'")
    long countActiveTargetLocales(@Param("projectId") long projectId);

    @Select("""
            SELECT p.id, p.owner_id, p.name, p.description, p.source_locale, p.version, p.status,
                   p.created_at, p.updated_at
              FROM projects p
              JOIN project_members m ON m.project_id = p.id
             WHERE m.user_id = #{userId} AND p.status IN ('ACTIVE', 'ARCHIVED')
             ORDER BY p.updated_at DESC, p.id DESC
            """)
    List<ProjectRow> findActiveForMember(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM external_identities WHERE user_id = #{userId}")
    long countIdentity(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM projects WHERE owner_id = #{ownerId} AND LOWER(name) = LOWER(#{name})")
    long countProjectName(@Param("ownerId") long ownerId, @Param("name") String name);

    @Update("UPDATE projects SET status = 'ACTIVE', updated_at = #{updatedAt} WHERE id = #{projectId} AND status = 'MIGRATING'")
    int activateMigrating(@Param("projectId") long projectId, @Param("updatedAt") Instant updatedAt);

    @Select("""
            <script>
            SELECT COUNT(*) FROM project_members m JOIN projects p ON p.id = m.project_id
             WHERE m.project_id = #{projectId} AND m.user_id = #{userId} AND p.status = 'ACTIVE'
               AND m.role IN
               <foreach collection='roles' item='role' open='(' separator=',' close=')'>
                   #{role}
               </foreach>
            </script>
            """)
    long countMemberRoles(@Param("projectId") long projectId,
                          @Param("userId") long userId,
                          @Param("roles") java.util.List<String> roles);

    @Select("""
            <script>
            SELECT COUNT(*) FROM project_members m
              JOIN projects p ON p.id = m.project_id
             WHERE m.project_id = #{projectId} AND m.user_id = #{userId}
               AND p.status = #{status} AND m.role IN
               <foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach>
            </script>
            """)
    long countMemberRolesByStatus(@Param("projectId") long projectId,
                                  @Param("userId") long userId,
                                  @Param("status") String status,
                                  @Param("roles") java.util.List<String> roles);

    @Select("""
            <script>
            SELECT COUNT(*) FROM project_members m JOIN projects p ON p.id = m.project_id
             WHERE m.project_id = #{projectId} AND m.user_id = #{userId}
               AND p.status IN ('ACTIVE', 'ARCHIVED') AND m.role IN
               <foreach collection='roles' item='role' open='(' separator=',' close=')'>#{role}</foreach>
            </script>
            """)
    long countAnyProjectMemberRoles(@Param("projectId") long projectId,
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

    @Update("UPDATE project_members SET role = #{role} WHERE project_id = #{projectId} AND user_id = #{userId}")
    int changeMemberRole(@Param("projectId") long projectId, @Param("userId") long userId,
                         @Param("role") String role);

    @org.apache.ibatis.annotations.Delete("DELETE FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    int removeMember(@Param("projectId") long projectId, @Param("userId") long userId);

    @Update("UPDATE projects SET owner_id = #{newOwnerId}, version = #{newVersion}, updated_at = #{updatedAt} WHERE id = #{projectId} AND owner_id = #{currentOwnerId} AND status = 'ACTIVE' AND version = #{expectedVersion}")
    int transferProjectOwner(@Param("projectId") long projectId,
                             @Param("currentOwnerId") long currentOwnerId,
                             @Param("newOwnerId") long newOwnerId,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("newVersion") long newVersion,
                             @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE projects SET status = #{status}, version = #{version}, updated_at = #{updatedAt} WHERE id = #{projectId} AND status = #{expectedStatus} AND version = #{expectedVersion}")
    int updateProjectStatus(@Param("projectId") long projectId, @Param("expectedStatus") String expectedStatus,
                            @Param("status") String status, @Param("expectedVersion") long expectedVersion,
                            @Param("version") long version, @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE project_languages SET status = #{status} WHERE project_id = #{projectId} AND locale = #{locale} AND status = #{expectedStatus}")
    int setTargetLocaleStatus(@Param("projectId") long projectId, @Param("locale") String locale,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("status") String status);

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

    @Select("SELECT id, project_id AS projectId, name, status FROM namespaces WHERE project_id = #{projectId} AND (#{includeArchived} = TRUE OR status = 'ACTIVE') ORDER BY name, id")
    List<NamespaceRow> findNamespaces(@Param("projectId") long projectId,
                                      @Param("includeArchived") boolean includeArchived);

    @Select("SELECT id, project_id AS projectId, name, status FROM namespaces WHERE project_id = #{projectId} AND id = #{namespaceId} AND status = 'ACTIVE' FOR UPDATE")
    NamespaceRow lockActiveNamespace(@Param("projectId") long projectId,
                                      @Param("namespaceId") long namespaceId);

    @Insert("INSERT INTO namespaces (id, project_id, name, status, created_by, created_at) VALUES (#{id}, #{projectId}, #{name}, #{status}, #{actorId}, #{createdAt})")
    int insertNamespace(@Param("id") long id, @Param("projectId") long projectId,
                        @Param("name") String name, @Param("status") String status,
                        @Param("actorId") long actorId, @Param("createdAt") Instant createdAt);

    @Update("UPDATE namespaces SET name = #{name} WHERE project_id = #{projectId} AND id = #{namespaceId} AND status = 'ACTIVE'")
    int renameNamespace(@Param("projectId") long projectId, @Param("namespaceId") long namespaceId,
                        @Param("name") String name);

    @Update("UPDATE namespaces SET status = 'ARCHIVED' WHERE project_id = #{projectId} AND id = #{namespaceId} AND status = 'ACTIVE' AND NOT EXISTS (SELECT 1 FROM catalog_entries WHERE project_id = #{projectId} AND namespace_id = #{namespaceId} AND status = 'ACTIVE')")
    int archiveNamespace(@Param("projectId") long projectId, @Param("namespaceId") long namespaceId);

    @Update("UPDATE namespaces SET status = 'ACTIVE' WHERE project_id = #{projectId} AND id = #{namespaceId} AND status = 'ARCHIVED'")
    int restoreNamespace(@Param("projectId") long projectId, @Param("namespaceId") long namespaceId);

    @Select("SELECT COUNT(*) > 0 FROM catalog_entries WHERE project_id = #{projectId} AND namespace_id = #{namespaceId}")
    boolean namespaceHasEntries(@Param("projectId") long projectId, @Param("namespaceId") long namespaceId);

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

    @Getter
    @Setter
    @NoArgsConstructor
    class NamespaceRow {
        private Long id;
        private Long projectId;
        private String name;
        private String status;
    }

    @TableName("projects")
    @Getter
    @Setter
    @NoArgsConstructor
    class ProjectRow {
        private Long id;
        private Long ownerId;
        private String name;
        private String description;
        private String sourceLocale;
        private Long version;
        private String status;
        private Instant createdAt;
        private Instant updatedAt;
    }
}
