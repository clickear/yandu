package com.yandu.workspace;

/** Project resource authorization contract shared by Workspace and consuming business modules. */
public interface ProjectAccessPolicy {

    void requireCanCreateProject(long actorId);

    void requireCanManageProject(long actorId, long projectId);

    void requireCanManageMembers(long actorId, long projectId);

    void requireCanReadProject(long actorId, long projectId);

    void requireCanManageCatalog(long actorId, long projectId);

    void requireCanReadCatalog(long actorId, long projectId);

    default void requireCanManageTranslation(long actorId, long projectId) {
        throw new SecurityException("Translation editing is not configured");
    }

    default void requireCanReviewTranslation(long actorId, long projectId) {
        throw new SecurityException("Translation review is not configured");
    }

    default void requireCanReadTranslation(long actorId, long projectId) {
        throw new SecurityException("Translation reading is not configured");
    }

    default void requireCanPublish(long actorId, long projectId) {
        throw new SecurityException("Project publishing is not configured");
    }
}
