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

    Optional<ProjectSnapshot> find(long projectId);

    List<ProjectSnapshot> findForMember(long actorId);

    boolean userExists(long userId);

    void addMember(long projectId, long userId, MemberRole role, Instant createdAt);

    List<ProjectMember> members(long projectId);

    void update(ProjectSnapshot project, Instant updatedAt);
}
