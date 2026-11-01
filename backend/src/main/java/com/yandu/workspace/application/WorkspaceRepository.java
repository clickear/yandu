package com.yandu.workspace.application;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import com.yandu.workspace.WorkspaceApplicationService.MemberRole;
import com.yandu.workspace.WorkspaceApplicationService.ProjectMember;
import com.yandu.workspace.WorkspaceApplicationService.ProjectSnapshot;

/** Persistence port for projects and their target locales. */
public interface WorkspaceRepository {

    void insert(ProjectSnapshot project, long namespaceId, String namespaceName, Instant createdAt);

    void insertMigrating(ProjectSnapshot project, String description, long namespaceId,
                         String namespaceName, Instant createdAt);

    void activateMigrating(long projectId, Instant updatedAt);

    boolean projectNameExists(long ownerId, String name);

    default Optional<ProjectSnapshot> findAny(long projectId) { return find(projectId); }

    Optional<ProjectSnapshot> find(long projectId);

    List<ProjectSnapshot> findForMember(long actorId);

    boolean userExists(long userId);

    void addMember(long projectId, long userId, MemberRole role, Instant createdAt);

    default void changeMemberRole(long projectId, long userId, MemberRole role, Instant updatedAt) {
        throw new UnsupportedOperationException();
    }

    default void removeMember(long projectId, long userId) { throw new UnsupportedOperationException(); }

    default void transferOwnership(long projectId, long currentOwnerId, long newOwnerId,
                                   long expectedVersion, long newVersion, Instant updatedAt) {
        throw new UnsupportedOperationException();
    }

    default void updateProjectStatus(ProjectSnapshot project, String expectedStatus, Instant updatedAt) {
        throw new UnsupportedOperationException();
    }

    default void setTargetLocaleStatus(long projectId, String locale, String expectedStatus,
                                       String status, Instant updatedAt) {
        throw new UnsupportedOperationException();
    }

    List<ProjectMember> members(long projectId);

    default List<String> archivedTargetLocales(long projectId) { return List.of(); }

    default List<String> findTargetLocales(long projectId) {
        return find(projectId).map(project -> List.copyOf(project.targetLocales())).orElseGet(List::of);
    }

    void update(ProjectSnapshot project, Instant updatedAt);
}
