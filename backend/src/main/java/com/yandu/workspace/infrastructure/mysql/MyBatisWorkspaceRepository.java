package com.yandu.workspace.infrastructure.mysql;

import com.yandu.workspace.WorkspaceApplicationService.ProjectSnapshot;
import com.yandu.workspace.WorkspaceApplicationService.MemberRole;
import com.yandu.workspace.WorkspaceApplicationService.ProjectMember;
import com.yandu.workspace.application.WorkspaceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisWorkspaceRepository implements WorkspaceRepository {

    private final WorkspaceMapper mapper;

    public MyBatisWorkspaceRepository(WorkspaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ProjectSnapshot project, long namespaceId, String namespaceName,
                       Instant createdAt) {
        insertProject(project, project.description(), namespaceId, namespaceName, createdAt, "ACTIVE");
    }

    @Override
    public void insertMigrating(ProjectSnapshot project, String description, long namespaceId,
                                String namespaceName, Instant createdAt) {
        insertProject(project, description, namespaceId, namespaceName, createdAt, "MIGRATING");
    }

    private void insertProject(ProjectSnapshot project, String description, long namespaceId,
                               String namespaceName, Instant createdAt, String status) {
        var row = new WorkspaceMapper.ProjectRow();
        row.setId(project.id());
        row.setOwnerId(project.ownerId());
        row.setName(project.name());
        row.setSourceLocale(project.sourceLocale());
        row.setVersion(project.version());
        row.setStatus(status);
        row.setDescription(description);
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        mapper.insert(row);
        mapper.insertOwnerMembership(project.id(), project.ownerId(), createdAt);
        mapper.insertDefaultNamespace(namespaceId, project.id(), namespaceName,
                project.ownerId(), createdAt);
        for (var locale : project.targetLocales()) {
            mapper.insertTargetLocale(project.id(), locale, createdAt);
        }
    }

    @Override public void activateMigrating(long projectId, Instant updatedAt) {
        if (mapper.activateMigrating(projectId, updatedAt) != 1) {
            throw new IllegalStateException("Migrating project could not be activated");
        }
    }

    @Override public boolean projectNameExists(long ownerId, String name) {
        return mapper.countProjectName(ownerId, name) > 0;
    }

    @Override public Optional<ProjectSnapshot> findAny(long projectId) {
        return Optional.ofNullable(mapper.selectById(projectId)).map(row ->
                new ProjectSnapshot(row.getId(), row.getOwnerId(), row.getName(), row.getSourceLocale(),
                        java.util.Set.copyOf(mapper.findTargetLocales(projectId)), row.getVersion(),
                        row.getDescription() == null ? "" : row.getDescription(), row.getStatus()));
    }

    @Override
    public Optional<ProjectSnapshot> find(long projectId) {
        var row = mapper.selectById(projectId);
        if (row == null || !"ACTIVE".equals(row.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(new ProjectSnapshot(row.getId(), row.getOwnerId(), row.getName(),
                row.getSourceLocale(), java.util.Set.copyOf(mapper.findTargetLocales(projectId)),
                row.getVersion(), row.getDescription(), row.getStatus()));
    }

    @Override
    public List<ProjectSnapshot> findForMember(long actorId) {
        return mapper.findActiveForMember(actorId).stream()
                .map(row -> new ProjectSnapshot(row.getId(), row.getOwnerId(), row.getName(),
                        row.getSourceLocale(), java.util.Set.copyOf(mapper.findTargetLocales(row.getId())),
                        row.getVersion(), row.getDescription(), row.getStatus()))
                .toList();
    }

    @Override
    public boolean userExists(long userId) {
        return mapper.countIdentity(userId) == 1;
    }

    @Override
    public void addMember(long projectId, long userId, MemberRole role, Instant createdAt) {
        mapper.addMember(projectId, userId, role.name(), createdAt);
    }

    @Override public void changeMemberRole(long projectId, long userId, MemberRole role, Instant updatedAt) {
        if (mapper.changeMemberRole(projectId, userId, role.name()) != 1) {
            throw new IllegalStateException("Project member changed concurrently; reload and retry");
        }
    }

    @Override public void removeMember(long projectId, long userId) {
        if (mapper.removeMember(projectId, userId) != 1) throw new IllegalStateException("Project member was not found");
    }

    @Override public void transferOwnership(long projectId, long currentOwnerId, long newOwnerId,
                                            long expectedVersion, long newVersion, Instant updatedAt) {
        if (mapper.changeMemberRole(projectId, currentOwnerId, MemberRole.ADMIN.name()) != 1
                || mapper.changeMemberRole(projectId, newOwnerId, MemberRole.OWNER.name()) != 1
                || mapper.transferProjectOwner(projectId, currentOwnerId, newOwnerId,
                        expectedVersion, newVersion, updatedAt) != 1) {
            throw new IllegalStateException("Project ownership changed concurrently; reload and retry");
        }
    }

    @Override public void updateProjectStatus(ProjectSnapshot project, String expectedStatus,
                                              Instant updatedAt) {
        if (mapper.updateProjectStatus(project.id(), expectedStatus, project.status(),
                project.version() - 1, project.version(), updatedAt) != 1) {
            throw new IllegalStateException("Project lifecycle changed concurrently; reload and retry");
        }
    }

    @Override public void setTargetLocaleStatus(long projectId, String locale, String expectedStatus,
                                                String status, Instant updatedAt) {
        if (mapper.setTargetLocaleStatus(projectId, locale, expectedStatus, status) != 1) {
            throw new IllegalStateException("Target locale lifecycle changed concurrently; reload and retry");
        }
    }

    @Override
    public List<ProjectMember> members(long projectId) {
        return mapper.findMembers(projectId).stream()
                .map(row -> new ProjectMember(row.getUserId(), MemberRole.valueOf(row.getRole()),
                        row.getCreatedAt()))
                .toList();
    }

    @Override public List<String> archivedTargetLocales(long projectId) {
        return mapper.findArchivedTargetLocales(projectId);
    }

    @Override public List<String> findTargetLocales(long projectId) {
        return mapper.findTargetLocales(projectId);
    }

    @Override
    public void update(ProjectSnapshot project, Instant updatedAt) {
        var changed = mapper.updateVersion(project.id(), project.version() - 1,
                project.version(), updatedAt);
        if (changed != 1) {
            throw new IllegalStateException("Project changed concurrently; reload and retry");
        }
        var oldLocales = mapper.findTargetLocales(project.id());
        for (var locale : project.targetLocales()) {
            if (!oldLocales.contains(locale)) {
                mapper.insertTargetLocale(project.id(), locale, updatedAt);
            }
        }
    }
}
