package com.yandu.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.yandu.audit.AuditLog;
import com.yandu.workspace.application.WorkspaceRepository;
import com.yandu.workspace.ProjectAccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class WorkspaceApplicationServiceTest {

    @Test
    void createsProjectAndDefaultNamespaceAfterAuthorization() {
        var repository = new InMemoryWorkspaceRepository();
        var service = service(repository, new AllowAllPolicy());

        var created = service.create(new WorkspaceApplicationService.CreateProject(
                11L, "Storefront", "zh-CN"));

        assertEquals("101", created.id());
        assertEquals("101", created.defaultNamespaceId());
        assertEquals(Set.of(), created.targetLocales());
        assertEquals("default", repository.defaultNamespaces.get(101L));
    }

    @Test
    void addingTargetLocaleUpdatesProjectOnceAndRejectsDuplicates() {
        var repository = new InMemoryWorkspaceRepository();
        repository.projects.put(101L, new WorkspaceApplicationService.ProjectSnapshot(
                101L, 11L, "Storefront", "zh-CN", Set.of(), 1));
        var service = service(repository, new AllowAllPolicy());

        var updated = service.addTargetLocale(
                new WorkspaceApplicationService.AddTargetLocale(11L, 101L, "en-US"));

        assertEquals(Set.of("en-US"), updated.targetLocales());
        assertEquals(2, updated.version());
        assertThrows(IllegalArgumentException.class, () -> service.addTargetLocale(
                new WorkspaceApplicationService.AddTargetLocale(11L, 101L, "en-US")));
    }

    @Test
    void invalidLocaleIsRejectedBeforePersistence() {
        var repository = new InMemoryWorkspaceRepository();
        var service = service(repository, new AllowAllPolicy());

        assertThrows(IllegalArgumentException.class, () -> service.create(
                new WorkspaceApplicationService.CreateProject(11L, "Storefront", "bad_locale")));
        assertEquals(0, repository.projects.size());
    }

    @Test
    void listsOnlyProjectsReturnedForTheAuthenticatedMember() {
        var repository = new InMemoryWorkspaceRepository();
        repository.projects.put(101L, new WorkspaceApplicationService.ProjectSnapshot(
                101L, 11L, "Storefront", "zh-CN", Set.of("en-US"), 2));

        var listed = service(repository, new AllowAllPolicy()).listProjects(11L);

        assertEquals(1, listed.size());
        assertEquals("Storefront", listed.getFirst().name());
    }

    @Test
    void ownerCanAddAnExistingUserWithAProjectRole() {
        var repository = new InMemoryWorkspaceRepository();
        repository.projects.put(101L, new WorkspaceApplicationService.ProjectSnapshot(
                101L, 11L, "Storefront", "zh-CN", Set.of(), 1));
        repository.users.add(44L);

        service(repository, new AllowAllPolicy()).addMember(
                new WorkspaceApplicationService.AddMember(11L, 101L, 44L,
                        WorkspaceApplicationService.MemberRole.TRANSLATOR));

        assertEquals(WorkspaceApplicationService.MemberRole.TRANSLATOR,
                repository.members.stream().filter(member -> member.userId() == 44L)
                        .findFirst().orElseThrow().role());
    }

    private static WorkspaceApplicationService service(
            WorkspaceRepository repository, ProjectAccessPolicy policy) {
        var ids = new long[] {100};
        return new WorkspaceApplicationService(repository, policy, () -> ++ids[0],
                Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC),
                mock(AuditLog.class));
    }

    private static final class AllowAllPolicy implements ProjectAccessPolicy {
        @Override
        public void requireCanCreateProject(long actorId) { }

        @Override
        public void requireCanManageProject(long actorId, long projectId) { }

        @Override
        public void requireCanManageMembers(long actorId, long projectId) { }

        @Override
        public void requireCanReadProject(long actorId, long projectId) { }

        @Override
        public void requireCanManageCatalog(long actorId, long projectId) { }

        @Override
        public void requireCanReadCatalog(long actorId, long projectId) { }
    }

    private static final class InMemoryWorkspaceRepository implements WorkspaceRepository {
        private final Map<Long, WorkspaceApplicationService.ProjectSnapshot> projects = new HashMap<>();
        private final Map<Long, String> defaultNamespaces = new HashMap<>();
        private final java.util.Set<Long> users = new java.util.HashSet<>();
        private final ArrayList<WorkspaceApplicationService.ProjectMember> members = new ArrayList<>();

        @Override
        public void insert(WorkspaceApplicationService.ProjectSnapshot project,
                           long namespaceId, String namespaceName, Instant createdAt) {
            projects.put(project.id(), project);
            defaultNamespaces.put(project.id(), namespaceName);
            members.add(new WorkspaceApplicationService.ProjectMember(
                    project.ownerId(), WorkspaceApplicationService.MemberRole.OWNER, createdAt));
        }

        @Override
        public Optional<WorkspaceApplicationService.ProjectSnapshot> find(long projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public List<WorkspaceApplicationService.ProjectSnapshot> findForMember(long actorId) {
            return projects.values().stream().filter(project -> project.ownerId() == actorId).toList();
        }

        @Override
        public boolean userExists(long userId) {
            return users.contains(userId);
        }

        @Override
        public void addMember(long projectId, long userId,
                              WorkspaceApplicationService.MemberRole role, Instant createdAt) {
            members.add(new WorkspaceApplicationService.ProjectMember(userId, role, createdAt));
        }

        @Override
        public List<WorkspaceApplicationService.ProjectMember> members(long projectId) {
            return List.copyOf(members);
        }

        @Override
        public void update(WorkspaceApplicationService.ProjectSnapshot project, Instant updatedAt) {
            projects.put(project.id(), project);
        }
    }
}
