package com.yandu.release.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for immutable project-locale release artifacts. */
public interface ReleaseRepository {

    ReleaseSnapshot create(long id, long projectId, String locale, String format,
                           String content, String manifest,
                           Long rollbackOfReleaseId,
                           String checksum, long actorId, Instant createdAt);

    Optional<ReleaseSnapshot> find(long projectId, long releaseId);

    List<ReleaseSnapshot> list(long projectId, String locale, String format, int limit);

    List<ReleaseLine> lines(long projectId, String locale);

    record ReleaseSnapshot(long id, long projectId, String locale, String format, long version,
                           Long rollbackOfReleaseId,
                           String content, String manifest, String checksum,
                           long publishedBy, Instant publishedAt) { }

    record ReleaseLine(long entryId, String key, String sourceText, long sourceRevision,
                       String translation, String status, long translationSourceRevision,
                       long translationVersion) { }
}
