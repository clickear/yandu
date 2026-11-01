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
        insertProject(project, null, namespaceId, namespaceName, createdAt, "ACTIVE");
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

    @Override
    public Optional<ProjectSnapshot> find(long projectId) {
        var row = mapper.selectById(projectId);
        if (row == null || !"ACTIVE".equals(row.getStatus())) {
            return Optional.empty();
        }
        return Optional.of(new ProjectSnapshot(row.getId(), row.getOwnerId(), row.getName(),
                row.getSourceLocale(), java.util.Set.copyOf(mapper.findTargetLocales(projectId)),
                row.getVersion(), row.getDescription()));
    }

    @Override
    public List<ProjectSnapshot> findForMember(long actorId) {
        return mapper.findActiveForMember(actorId).stream()
                .map(row -> new ProjectSnapshot(row.getId(), row.getOwnerId(), row.getName(),
                        row.getSourceLocale(), java.util.Set.copyOf(mapper.findTargetLocales(row.getId())),
                        row.getVersion(), row.getDescription()))
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

    @Override
    public List<ProjectMember> members(long projectId) {
        return mapper.findMembers(projectId).stream()
                .map(row -> new ProjectMember(row.getUserId(), MemberRole.valueOf(row.getRole()),
                        row.getCreatedAt()))
                .toList();
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
