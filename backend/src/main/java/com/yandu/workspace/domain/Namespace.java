package com.yandu.workspace.domain;

/** A project resource group; only empty namespaces can be archived. */
public record Namespace(long id, long projectId, String name, Status status) {
    public Namespace {
        if (id <= 0 || projectId <= 0 || status == null) {
            throw new IllegalArgumentException("A namespace requires valid IDs and status");
        }
        name = validateName(name);
    }

    public Namespace rename(String newName) {
        return new Namespace(id, projectId, validateName(newName), status);
    }

    public Namespace archive(boolean hasEntries) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Only active namespaces can be archived");
        if (hasEntries) throw new IllegalStateException("Only empty namespaces can be archived");
        return new Namespace(id, projectId, name, Status.ARCHIVED);
    }

    public Namespace restore() {
        if (status != Status.ARCHIVED) throw new IllegalStateException("Only archived namespaces can be restored");
        return new Namespace(id, projectId, name, Status.ACTIVE);
    }

    private static String validateName(String name) {
        if (name == null) throw new IllegalArgumentException("Namespace name is required");
        var value = name.trim();
        if (value.isEmpty() || value.length() > 120) {
            throw new IllegalArgumentException("Namespace name must contain 1 to 120 characters");
        }
        return value;
    }

    public enum Status { ACTIVE, ARCHIVED }
}
