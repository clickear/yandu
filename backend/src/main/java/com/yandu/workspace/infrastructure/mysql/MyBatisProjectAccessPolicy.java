package com.yandu.workspace.infrastructure.mysql;

import com.yandu.workspace.ProjectAccessPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Enforces project membership against the trusted identity resolved from a validated JWT. */
@Component
public class MyBatisProjectAccessPolicy implements ProjectAccessPolicy {

    private final WorkspaceMapper mapper;

    public MyBatisProjectAccessPolicy(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void requireCanCreateProject(long actorId) {
        requireActor(actorId);
    }

    @Override
    public void requireCanManageProject(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN");
    }

    @Override
    public void requireCanManageMigration(long actorId, long projectId) {
        requireActor(actorId);
        if (mapper.countMemberRolesByStatus(projectId, actorId, "MIGRATING",
                java.util.List.of("OWNER")) != 1L) {
            throw new AccessDeniedException("Only the migration Owner can write to a migrating project");
        }
    }

    @Override
    public void requireCanManageMembers(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN");
    }

    @Override
    public void requireCanRestoreProject(long actorId, long projectId) {
        requireActor(actorId);
        if (mapper.countMemberRolesByStatus(projectId, actorId, "ARCHIVED",
                java.util.List.of("OWNER", "ADMIN")) != 1L) {
            throw new AccessDeniedException("Only a project Owner or Admin can restore an archived project");
        }
    }

    @Override
    public void requireCanTransferOwnership(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER");
    }

    @Override
    public void requireCanReadProject(long actorId, long projectId) {
        requireReadableMembership(actorId, projectId, "OWNER", "ADMIN", "TRANSLATOR", "REVIEWER", "VIEWER");
    }

    private void requireReadableMembership(long actorId, long projectId, String... roles) {
        requireActor(actorId);
        if (mapper.countAnyProjectMemberRoles(projectId, actorId,
                java.util.List.of(roles)) != 1L) {
            throw new AccessDeniedException("Project membership does not allow this operation");
        }
    }

    @Override
    public void requireCanManageCatalog(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN");
    }

    @Override
    public void requireCanReadCatalog(long actorId, long projectId) {
        requireReadableMembership(actorId, projectId, "OWNER", "ADMIN", "TRANSLATOR", "REVIEWER", "VIEWER");
    }

    @Override
    public void requireCanManageTranslation(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN", "TRANSLATOR");
    }

    @Override
    public void requireCanReviewTranslation(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN", "REVIEWER");
    }

    @Override
    public void requireCanReadTranslation(long actorId, long projectId) {
        requireReadableMembership(actorId, projectId, "OWNER", "ADMIN", "TRANSLATOR", "REVIEWER", "VIEWER");
    }

    @Override
    public void requireCanPublish(long actorId, long projectId) {
        requireMembership(actorId, projectId, "OWNER", "ADMIN");
    }

    private void requireMembership(long actorId, long projectId, String... roles) {
        requireActor(actorId);
        if (mapper.countMemberRoles(projectId, actorId, java.util.List.of(roles)) != 1L) {
            throw new AccessDeniedException("Project membership does not allow this operation");
        }
    }

    private static void requireActor(long actorId) {
        if (actorId <= 0) throw new AccessDeniedException("An authenticated actor is required");
    }
}
