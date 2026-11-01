package com.yandu.workspace.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Project aggregate owns its source locale and the set of configured target locales. */
public final class Project {

    private static final Pattern LOCALE = Pattern.compile("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*");

    private final long id;
    private long ownerId;
    private final String name;
    private final String description;
    private final String sourceLocale;
    private final Set<String> targetLocales;
    private long version;
    private Status status;

    public Project(long id, long ownerId, String name, String sourceLocale,
                   Set<String> targetLocales, long version) {
        this(id, ownerId, name, "", sourceLocale, targetLocales, version, Status.ACTIVE);
    }

    public Project(long id, long ownerId, String name, String description, String sourceLocale,
                   Set<String> targetLocales, long version, Status status) {
        if (id <= 0 || ownerId <= 0 || version <= 0) {
            throw new IllegalArgumentException("id, ownerId and version must be positive");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.name = requireName(name);
        this.description = requireDescription(description);
        this.sourceLocale = validateLocale(sourceLocale);
        if (targetLocales == null || targetLocales.isEmpty()) {
            throw new IllegalArgumentException("project must have at least one active target locale");
        }
        this.targetLocales = new LinkedHashSet<>();
        for (String locale : targetLocales) {
            var validLocale = validateLocale(locale);
            if (this.sourceLocale.equalsIgnoreCase(validLocale)
                    || !this.targetLocales.add(validLocale)) {
                throw new IllegalArgumentException("target locales must be unique and differ from source locale");
            }
        }
        this.version = version;
        this.status = java.util.Objects.requireNonNull(status);
    }

    public void addTargetLocale(String locale) {
        requireActive();
        var validLocale = validateLocale(locale);
        if (sourceLocale.equalsIgnoreCase(validLocale)) {
            throw new IllegalArgumentException("target locale cannot equal the source locale");
        }
        if (!targetLocales.add(validLocale)) {
            throw new IllegalArgumentException("target locale is already configured: " + validLocale);
        }
        version = Math.incrementExact(version);
    }

    public void archiveTargetLocale(String locale) {
        requireActive();
        var validLocale = validateLocale(locale);
        if (!targetLocales.contains(validLocale)) {
            throw new IllegalArgumentException("target locale is not active: " + validLocale);
        }
        if (targetLocales.size() == 1) {
            throw new IllegalStateException("At least one active target locale must remain");
        }
        targetLocales.remove(validLocale);
        version = Math.incrementExact(version);
    }

    public void archive() {
        if (status != Status.ACTIVE) throw new IllegalStateException("Only an active project can be archived");
        status = Status.ARCHIVED;
        version = Math.incrementExact(version);
    }

    public void restore() {
        if (status != Status.ARCHIVED) throw new IllegalStateException("Only an archived project can be restored");
        status = Status.ACTIVE;
        version = Math.incrementExact(version);
    }

    public void transferOwnership(long actorId, long newOwnerId, MemberRole newOwnerRole) {
        requireActive();
        if (actorId != ownerId) throw new PermissionDeniedException("Only the current Owner can transfer ownership");
        if (newOwnerId <= 0 || newOwnerId == ownerId || newOwnerRole != MemberRole.ADMIN) {
            throw new IllegalArgumentException("Ownership can only be transferred to a different existing Admin");
        }
        ownerId = newOwnerId;
        version = Math.incrementExact(version);
    }

    public void assertCanAddMember(long actorId, MemberRole actorRole, MemberRole newRole) {
        requireActive();
        requireManager(actorId, actorRole);
        if (newRole == null || newRole == MemberRole.OWNER) {
            throw new IllegalArgumentException("Owner membership can only change through ownership transfer");
        }
        if (actorRole == MemberRole.ADMIN && newRole == MemberRole.ADMIN) {
            throw new PermissionDeniedException("Only the Owner can grant the Admin role");
        }
    }

    public void assertCanChangeMemberRole(long actorId, MemberRole actorRole, long targetUserId,
                                          MemberRole currentRole, MemberRole newRole) {
        requireActive();
        requireManager(actorId, actorRole);
        if (targetUserId == ownerId || currentRole == MemberRole.OWNER || newRole == MemberRole.OWNER) {
            throw new PermissionDeniedException("Ownership can only change through ownership transfer");
        }
        if (targetUserId == actorId) throw new PermissionDeniedException("Members cannot change their own role");
        if (actorRole == MemberRole.ADMIN && (currentRole == MemberRole.ADMIN || newRole == MemberRole.ADMIN)) {
            throw new PermissionDeniedException("Admins can manage Translator, Reviewer and Viewer members only");
        }
    }

    public void assertCanRemoveMember(long actorId, MemberRole actorRole, long targetUserId,
                                     MemberRole targetRole) {
        requireActive();
        requireManager(actorId, actorRole);
        if (targetUserId == ownerId || targetRole == MemberRole.OWNER || targetUserId == actorId) {
            throw new PermissionDeniedException("The Owner cannot be removed; other members must leave themselves");
        }
        if (actorRole == MemberRole.ADMIN && targetRole == MemberRole.ADMIN) {
            throw new PermissionDeniedException("Only the Owner can manage Admin members");
        }
    }

    public void assertCanLeave(long actorId, MemberRole role) {
        if (actorId == ownerId || role == MemberRole.OWNER) {
            throw new IllegalStateException("Transfer ownership before leaving the project");
        }
    }

    private void requireManager(long actorId, MemberRole role) {
        if (actorId == ownerId && role == MemberRole.OWNER) return;
        if (actorId != ownerId && role == MemberRole.ADMIN) return;
        throw new PermissionDeniedException("Project Owner or Admin role is required");
    }

    private void requireActive() {
        if (status != Status.ACTIVE) throw new IllegalStateException("Project is not active");
    }

    public long id() { return id; }
    public long ownerId() { return ownerId; }
    public String name() { return name; }
    public String description() { return description; }
    public String sourceLocale() { return sourceLocale; }
    public Set<String> targetLocales() { return Set.copyOf(targetLocales); }
    public long version() { return version; }
    public Status status() { return status; }

    public static String validateLocale(String locale) {
        if (locale == null || !LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        var parsed = Locale.forLanguageTag(locale);
        if (parsed.getLanguage().isEmpty() || parsed.toLanguageTag().equals("und")) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        return parsed.toLanguageTag();
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 120) {
            throw new IllegalArgumentException("project name must contain 1 to 120 characters");
        }
        return value.strip();
    }

    private static String requireDescription(String value) {
        var description = value == null ? "" : value.trim();
        if (description.length() > 2000) throw new IllegalArgumentException("description must contain at most 2000 characters");
        return description;
    }

    public enum Status { ACTIVE, ARCHIVED, MIGRATING }

    public enum MemberRole { OWNER, ADMIN, TRANSLATOR, REVIEWER, VIEWER }

    public static final class PermissionDeniedException extends SecurityException {
        public PermissionDeniedException(String message) { super(message); }
    }
}
