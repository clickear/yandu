package com.yandu.workspace;

import com.yandu.audit.AuditLog;
import com.yandu.workspace.application.WorkspaceRepository;
import com.yandu.workspace.domain.Project;
import com.yandu.workspace.domain.Namespace;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates project setup and language configuration. */
@Service
public class WorkspaceApplicationService {

    private final WorkspaceRepository projects;
    private final ProjectAccessPolicy access;
    private final LongSupplier ids;
    private final Clock clock;
    private final AuditLog audit;

    public WorkspaceApplicationService(WorkspaceRepository projects, ProjectAccessPolicy access,
                                       LongSupplier ids, Clock clock, AuditLog audit) {
        this.projects = Objects.requireNonNull(projects);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public ProjectView create(CreateProject command) {
        access.requireCanCreateProject(command.actorId());
        var targetLocales = command.targetLocales() == null || command.targetLocales().isEmpty()
                ? Set.of(defaultTargetLocale(command.sourceLocale())) : command.targetLocales();
        var description = command.description() == null ? "" : command.description().trim();
        if (description.length() > 2000) throw new IllegalArgumentException("description is too long");
        var project = new Project(ids.getAsLong(), command.actorId(), command.name(), description,
                command.sourceLocale(), targetLocales, 1, Project.Status.ACTIVE);
        // The default namespace shares the project's numeric ID; IDs are unique per table.
        var namespaceId = project.id();
        projects.insert(snapshot(project), namespaceId, "default", clock.instant());
        audit.record(project.id(), command.actorId(), "PROJECT_CREATED", "PROJECT", project.id(),
                java.util.Map.of("sourceLocale", project.sourceLocale()));
        return view(snapshot(project));
    }

    /** Creates a hidden project shell for the resumable local-project migration workflow. */
    @Transactional
    public long createMigratingProject(long actorId, String name, String description,
                                       String sourceLocale, Set<String> targetLocales) {
        access.requireCanCreateProject(actorId);
        if (targetLocales == null || targetLocales.isEmpty()) {
            throw new IllegalArgumentException("At least one target locale is required");
        }
        var normalized = targetLocales.stream().map(Project::validateLocale)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var project = new Project(ids.getAsLong(), actorId, name, description, sourceLocale,
                normalized, 1, Project.Status.MIGRATING);
        var now = clock.instant();
        projects.insertMigrating(snapshot(project), description,
                project.id(), "default", now);
        audit.record(project.id(), actorId, "PROJECT_MIGRATION_STARTED", "PROJECT",
                project.id(), java.util.Map.of("sourceLocale", project.sourceLocale()));
        return project.id();
    }

    @Transactional
    public void activateMigratingProject(long projectId) {
        projects.activateMigrating(projectId, clock.instant());
    }

    @Transactional(readOnly = true)
    public boolean projectNameExists(long ownerId, String name) {
        return projects.projectNameExists(ownerId, name);
    }

    @Transactional
    public ProjectView addTargetLocale(AddTargetLocale command) {
        access.requireCanManageProject(command.actorId(), command.projectId());
        var existing = projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        var project = toDomain(existing);
        var locale = Project.validateLocale(command.locale());
        var restoring = projects.archivedTargetLocales(command.projectId()).contains(locale);
        if (restoring) projects.setTargetLocaleStatus(command.projectId(), locale, "ARCHIVED", "ACTIVE", clock.instant());
        project.addTargetLocale(locale);
        var updated = snapshot(project);
        projects.update(updated, clock.instant());
        audit.record(project.id(), command.actorId(), restoring ? "TARGET_LOCALE_RESTORED" : "TARGET_LOCALE_ADDED",
                "PROJECT_LANGUAGE", null, java.util.Map.of("locale", locale, "projectVersion", project.version()));
        return view(updated);
    }

    @Transactional(readOnly = true)
    public List<NamespaceView> namespaces(long actorId, long projectId, boolean includeArchived) {
        access.requireCanReadProject(actorId, projectId);
        projects.findAny(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projects.namespaces(projectId, includeArchived).stream().map(NamespaceView::from).toList();
    }

    /** Holds the namespace row lock until the caller's transaction inserts its resource. */
    @Transactional
    public void requireActiveNamespace(long projectId, long namespaceId) {
        if (!projects.lockActiveNamespace(projectId, namespaceId)) {
            throw new IllegalArgumentException("Namespace is not active in this project");
        }
    }

    @Transactional
    public NamespaceView createNamespace(CreateNamespace command) {
        access.requireCanManageProject(command.actorId(), command.projectId());
        projects.find(command.projectId()).orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        var namespace = new Namespace(ids.getAsLong(), command.projectId(), command.name(), Namespace.Status.ACTIVE);
        projects.insertNamespace(namespace, command.actorId(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "NAMESPACE_CREATED", "NAMESPACE",
                namespace.id(), java.util.Map.of("name", namespace.name()));
        return NamespaceView.from(namespace);
    }

    @Transactional
    public NamespaceView renameNamespace(RenameNamespace command) {
        access.requireCanManageProject(command.actorId(), command.projectId());
        var namespace = findNamespace(command.projectId(), command.namespaceId(), false)
                .rename(command.name());
        if (!projects.renameNamespace(command.projectId(), namespace.id(), namespace.name())) {
            throw new IllegalStateException("Namespace changed concurrently; reload and retry");
        }
        audit.record(command.projectId(), command.actorId(), "NAMESPACE_RENAMED", "NAMESPACE",
                namespace.id(), java.util.Map.of("name", namespace.name()));
        return NamespaceView.from(namespace);
    }

    @Transactional
    public void archiveNamespace(NamespaceCommand command) {
        access.requireCanManageProject(command.actorId(), command.projectId());
        var namespace = findNamespace(command.projectId(), command.namespaceId(), false)
                .archive(projects.namespaceHasEntries(command.projectId(), command.namespaceId()));
        if (!projects.archiveNamespace(command.projectId(), namespace.id())) {
            throw new IllegalStateException("Namespace is not empty or changed concurrently; reload and retry");
        }
        audit.record(command.projectId(), command.actorId(), "NAMESPACE_ARCHIVED", "NAMESPACE",
                namespace.id(), java.util.Map.of());
    }

    @Transactional
    public NamespaceView restoreNamespace(NamespaceCommand command) {
        access.requireCanManageProject(command.actorId(), command.projectId());
        var namespace = findNamespace(command.projectId(), command.namespaceId(), true).restore();
        if (!projects.restoreNamespace(command.projectId(), namespace.id())) {
            throw new IllegalStateException("Namespace could not be restored; reload and retry");
        }
        audit.record(command.projectId(), command.actorId(), "NAMESPACE_RESTORED", "NAMESPACE",
                namespace.id(), java.util.Map.of());
        return NamespaceView.from(namespace);
    }

    private Namespace findNamespace(long projectId, long namespaceId, boolean includeArchived) {
        return projects.namespaces(projectId, includeArchived).stream()
                .filter(namespace -> namespace.id() == namespaceId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Namespace not found in project"));
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        var project = projects.findAny(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        return view(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectView> listProjects(long actorId) {
        if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        return projects.findForMember(actorId).stream().map(WorkspaceApplicationService::view).toList();
    }

    @Transactional
    public void addMember(AddMember command) {
        access.requireCanManageMembers(command.actorId(), command.projectId());
        projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        var project = toDomain(projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId())));
        var actorRole = projects.members(command.projectId()).stream()
                .filter(member -> member.userId() == command.actorId()).map(ProjectMember::role)
                .findFirst().orElseThrow(() -> new SecurityException("Project membership is required"));
        project.assertCanAddMember(command.actorId(), domainRole(actorRole), domainRole(command.role()));
        if (!projects.userExists(command.userId())) {
            throw new UserNotFoundException(command.userId());
        }
        projects.addMember(command.projectId(), command.userId(), command.role(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "PROJECT_MEMBER_ADDED", "PROJECT_MEMBER",
                command.userId(), java.util.Map.of("role", command.role().name()));
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> members(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        projects.findAny(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projects.members(projectId);
    }

    @Transactional(readOnly = true)
    public List<String> archivedTargetLocales(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        projects.findAny(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projects.archivedTargetLocales(projectId);
    }

    @Transactional
    public void changeMemberRole(ChangeMemberRole command) {
        access.requireCanManageMembers(command.actorId(), command.projectId());
        var project = toDomain(projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId())));
        var members = projects.members(command.projectId());
        var actorRole = roleOf(members, command.actorId());
        var current = roleOf(members, command.userId());
        project.assertCanChangeMemberRole(command.actorId(), domainRole(actorRole), command.userId(),
                domainRole(current), domainRole(command.role()));
        projects.changeMemberRole(command.projectId(), command.userId(), command.role(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "PROJECT_MEMBER_ROLE_CHANGED", "PROJECT_MEMBER",
                command.userId(), java.util.Map.of("fromRole", current.name(), "toRole", command.role().name()));
    }

    @Transactional
    public void removeMember(RemoveMember command) {
        access.requireCanManageMembers(command.actorId(), command.projectId());
        var project = toDomain(projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId())));
        var members = projects.members(command.projectId());
        var actorRole = roleOf(members, command.actorId());
        var targetRole = roleOf(members, command.userId());
        project.assertCanRemoveMember(command.actorId(), domainRole(actorRole), command.userId(), domainRole(targetRole));
        projects.removeMember(command.projectId(), command.userId());
        audit.record(command.projectId(), command.actorId(), "PROJECT_MEMBER_REMOVED", "PROJECT_MEMBER",
                command.userId(), java.util.Map.of("role", targetRole.name()));
    }

    @Transactional
    public void leaveProject(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        var project = toDomain(projects.findAny(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId)));
        var role = roleOf(projects.members(projectId), actorId);
        project.assertCanLeave(actorId, domainRole(role));
        projects.removeMember(projectId, actorId);
        audit.record(projectId, actorId, "PROJECT_MEMBER_LEFT", "PROJECT_MEMBER", actorId,
                java.util.Map.of("role", role.name()));
    }

    @Transactional
    public void transferOwnership(TransferOwnership command) {
        access.requireCanTransferOwnership(command.actorId(), command.projectId());
        var existing = projects.find(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        var project = toDomain(existing);
        var role = roleOf(projects.members(command.projectId()), command.newOwnerId());
        project.transferOwnership(command.actorId(), command.newOwnerId(), domainRole(role));
        projects.transferOwnership(command.projectId(), existing.ownerId(), command.newOwnerId(),
                existing.version(), project.version(), clock.instant());
        audit.record(command.projectId(), command.actorId(), "PROJECT_OWNERSHIP_TRANSFERRED", "PROJECT",
                command.projectId(), java.util.Map.of("newOwnerId", command.newOwnerId()));
    }

    @Transactional
    public void archiveProject(long actorId, long projectId) {
        access.requireCanManageProject(actorId, projectId);
        var project = toDomain(projects.find(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId)));
        project.archive();
        projects.updateProjectStatus(snapshot(project), "ACTIVE", clock.instant());
        audit.record(projectId, actorId, "PROJECT_ARCHIVED", "PROJECT", projectId, java.util.Map.of());
    }

    @Transactional
    public void restoreProject(long actorId, long projectId) {
        access.requireCanRestoreProject(actorId, projectId);
        var project = toDomain(projects.findAny(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId)));
        project.restore();
        projects.updateProjectStatus(snapshot(project), "ARCHIVED", clock.instant());
        audit.record(projectId, actorId, "PROJECT_RESTORED", "PROJECT", projectId, java.util.Map.of());
    }

    @Transactional
    public List<String> archiveTargetLocale(long actorId, long projectId, String locale) {
        access.requireCanManageProject(actorId, projectId);
        var normalized = Project.validateLocale(locale);
        var existing = projects.find(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        var project = toDomain(existing);
        project.archiveTargetLocale(normalized);
        projects.setTargetLocaleStatus(projectId, normalized, "ACTIVE", "ARCHIVED", clock.instant());
        var updated = snapshot(project);
        projects.update(updated, clock.instant());
        audit.record(projectId, actorId, "TARGET_LOCALE_ARCHIVED", "PROJECT_LANGUAGE", null,
                java.util.Map.of("locale", normalized));
        return List.copyOf(project.targetLocales());
    }

    @Transactional
    public List<String> restoreTargetLocale(long actorId, long projectId, String locale) {
        access.requireCanManageProject(actorId, projectId);
        var normalized = Project.validateLocale(locale);
        if (!projects.archivedTargetLocales(projectId).contains(normalized)) {
            throw new IllegalArgumentException("Target locale is not archived in this project");
        }
        var existing = projects.find(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        var project = toDomain(existing);
        project.addTargetLocale(normalized);
        projects.setTargetLocaleStatus(projectId, normalized, "ARCHIVED", "ACTIVE", clock.instant());
        var updated = snapshot(project);
        projects.update(updated, clock.instant());
        audit.record(projectId, actorId, "TARGET_LOCALE_RESTORED", "PROJECT_LANGUAGE", null,
                java.util.Map.of("locale", normalized));
        return List.copyOf(project.targetLocales());
    }

    private static Project toDomain(ProjectSnapshot snapshot) {
        return new Project(snapshot.id(), snapshot.ownerId(), snapshot.name(),
                snapshot.description(), snapshot.sourceLocale(), snapshot.targetLocales(),
                snapshot.version(), Project.Status.valueOf(snapshot.status()));
    }

    private static ProjectSnapshot snapshot(Project project) {
        return new ProjectSnapshot(project.id(), project.ownerId(), project.name(),
                project.sourceLocale(), project.targetLocales(), project.version(),
                project.description(), project.status().name());
    }

    private static MemberRole roleOf(List<ProjectMember> members, long userId) {
        return members.stream().filter(member -> member.userId() == userId).map(ProjectMember::role)
                .findFirst().orElseThrow(() -> new UserNotFoundException(userId));
    }

    private static Project.MemberRole domainRole(MemberRole role) {
        return Project.MemberRole.valueOf(role.name());
    }

    private static String defaultTargetLocale(String sourceLocale) {
        return "en-US".equalsIgnoreCase(Project.validateLocale(sourceLocale)) ? "zh-CN" : "en-US";
    }

    private static ProjectView view(ProjectSnapshot project) {
        return new ProjectView(Long.toString(project.id()), Long.toString(project.ownerId()),
                project.name(), project.sourceLocale(), project.targetLocales(),
                project.version(), Long.toString(project.id()), project.description(), project.status());
    }

    public static String normalizeLocale(String locale) {
        return Project.validateLocale(locale);
    }

    public record CreateProject(long actorId, String name, String sourceLocale,
                                String description, Set<String> targetLocales) {
        public CreateProject(long actorId, String name, String sourceLocale) {
            this(actorId, name, sourceLocale, "", null);
        }
        public CreateProject {
            if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
            description = description == null ? "" : description;
        }
    }

    public record AddTargetLocale(long actorId, long projectId, String locale) {
        public AddTargetLocale {
            if (actorId <= 0 || projectId <= 0) {
                throw new IllegalArgumentException("actorId and projectId must be positive");
            }
        }
    }

    public record CreateNamespace(long actorId, long projectId, String name) { }
    public record RenameNamespace(long actorId, long projectId, long namespaceId, String name) { }
    public record NamespaceCommand(long actorId, long projectId, long namespaceId) { }
    public record NamespaceView(String id, String projectId, String name, String status) {
        private static NamespaceView from(Namespace namespace) {
            return new NamespaceView(Long.toString(namespace.id()), Long.toString(namespace.projectId()),
                    namespace.name(), namespace.status().name());
        }
    }

    public record AddMember(long actorId, long projectId, long userId, MemberRole role) {
        public AddMember {
            if (actorId <= 0 || projectId <= 0 || userId <= 0 || role == null
                    || role == MemberRole.OWNER) {
                throw new IllegalArgumentException("actorId, projectId, userId and role are required");
            }
        }
    }

    public record ChangeMemberRole(long actorId, long projectId, long userId, MemberRole role) { }
    public record RemoveMember(long actorId, long projectId, long userId) { }
    public record TransferOwnership(long actorId, long projectId, long newOwnerId) { }

    public enum MemberRole { OWNER, ADMIN, TRANSLATOR, REVIEWER, VIEWER }

    public record ProjectMember(long userId, MemberRole role, Instant createdAt) {
        public ProjectMember {
            if (userId <= 0 || role == null || createdAt == null) {
                throw new IllegalArgumentException("A project member requires an ID, role and creation time");
            }
        }
    }

    public record ProjectSnapshot(long id, long ownerId, String name, String sourceLocale,
                                  Set<String> targetLocales, long version, String description,
                                  String status) {
        public ProjectSnapshot(long id, long ownerId, String name, String sourceLocale,
                               Set<String> targetLocales, long version, String description) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, description, "ACTIVE");
        }
        public ProjectSnapshot(long id, long ownerId, String name, String sourceLocale,
                              Set<String> targetLocales, long version) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, "", "ACTIVE");
        }
    }

    public record ProjectView(String id, String ownerId, String name, String sourceLocale,
                              Set<String> targetLocales, long version, String defaultNamespaceId,
                              String description, String status) {
        public ProjectView(String id, String ownerId, String name, String sourceLocale,
                           Set<String> targetLocales, long version, String defaultNamespaceId,
                           String description) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, defaultNamespaceId,
                    description, "ACTIVE");
        }
        public ProjectView(String id, String ownerId, String name, String sourceLocale,
                           Set<String> targetLocales, long version, String defaultNamespaceId) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, defaultNamespaceId, "", "ACTIVE");
        }
    }

    public static final class ProjectNotFoundException extends RuntimeException {
        public ProjectNotFoundException(long projectId) {
            super("Project not found: " + projectId);
        }
    }

    public static final class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(long userId) {
            super("User not found: " + userId);
        }
    }
}
