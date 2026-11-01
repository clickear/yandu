package com.yandu.workspace;

import com.yandu.audit.AuditLog;
import com.yandu.workspace.application.WorkspaceRepository;
import com.yandu.workspace.domain.Project;
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
        var project = new Project(ids.getAsLong(), command.actorId(), command.name(),
                command.sourceLocale(), Set.of(), 1);
        // The default namespace shares the project's numeric ID; IDs are unique per table.
        var namespaceId = project.id();
        projects.insert(snapshot(project), namespaceId, "default", clock.instant());
        audit.record(project.id(), command.actorId(), "PROJECT_CREATED", "PROJECT", project.id(),
                java.util.Map.of("sourceLocale", project.sourceLocale()));
        return view(project);
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
        var project = new Project(ids.getAsLong(), actorId, name, sourceLocale, normalized, 1);
        var now = clock.instant();
        projects.insertMigrating(snapshot(project, description), description, project.id(), "default", now);
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
        project.addTargetLocale(command.locale());
        var updated = snapshot(project, existing.description());
        projects.update(updated, clock.instant());
        audit.record(project.id(), command.actorId(), "TARGET_LOCALE_ADDED", "PROJECT_LANGUAGE",
                null, java.util.Map.of("locale", command.locale(), "projectVersion", project.version()));
        return view(updated);
    }

    @Transactional(readOnly = true)
    public ProjectView getProject(long actorId, long projectId) {
        access.requireCanReadProject(actorId, projectId);
        var project = projects.find(projectId)
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
        projects.find(projectId).orElseThrow(() -> new ProjectNotFoundException(projectId));
        return projects.members(projectId);
    }

    private static Project toDomain(ProjectSnapshot snapshot) {
        return new Project(snapshot.id(), snapshot.ownerId(), snapshot.name(),
                snapshot.sourceLocale(), snapshot.targetLocales(), snapshot.version());
    }

    private static ProjectSnapshot snapshot(Project project) {
        return new ProjectSnapshot(project.id(), project.ownerId(), project.name(),
                project.sourceLocale(), project.targetLocales(), project.version(), "");
    }

    private static ProjectSnapshot snapshot(Project project, String description) {
        return new ProjectSnapshot(project.id(), project.ownerId(), project.name(),
                project.sourceLocale(), project.targetLocales(), project.version(),
                description == null ? "" : description);
    }

    private static ProjectView view(Project project) {
        return new ProjectView(Long.toString(project.id()), Long.toString(project.ownerId()),
                project.name(), project.sourceLocale(), project.targetLocales(),
                project.version(), Long.toString(project.id()), "");
    }

    private static ProjectView view(ProjectSnapshot project) {
        return new ProjectView(Long.toString(project.id()), Long.toString(project.ownerId()),
                project.name(), project.sourceLocale(), project.targetLocales(),
                project.version(), Long.toString(project.id()), project.description());
    }

    public static String normalizeLocale(String locale) {
        return Project.validateLocale(locale);
    }

    public record CreateProject(long actorId, String name, String sourceLocale) {
        public CreateProject {
            if (actorId <= 0) throw new IllegalArgumentException("actorId must be positive");
        }
    }

    public record AddTargetLocale(long actorId, long projectId, String locale) {
        public AddTargetLocale {
            if (actorId <= 0 || projectId <= 0) {
                throw new IllegalArgumentException("actorId and projectId must be positive");
            }
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

    public enum MemberRole { OWNER, ADMIN, TRANSLATOR, REVIEWER, VIEWER }

    public record ProjectMember(long userId, MemberRole role, Instant createdAt) {
        public ProjectMember {
            if (userId <= 0 || role == null || createdAt == null) {
                throw new IllegalArgumentException("A project member requires an ID, role and creation time");
            }
        }
    }

    public record ProjectSnapshot(long id, long ownerId, String name, String sourceLocale,
                                  Set<String> targetLocales, long version, String description) {
        public ProjectSnapshot(long id, long ownerId, String name, String sourceLocale,
                              Set<String> targetLocales, long version) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, "");
        }
    }

    public record ProjectView(String id, String ownerId, String name, String sourceLocale,
                              Set<String> targetLocales, long version, String defaultNamespaceId,
                              String description) {
        public ProjectView(String id, String ownerId, String name, String sourceLocale,
                           Set<String> targetLocales, long version, String defaultNamespaceId) {
            this(id, ownerId, name, sourceLocale, targetLocales, version, defaultNamespaceId, "");
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
