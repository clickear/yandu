package com.yandu.release.infrastructure.mysql;

import com.yandu.release.application.ReleaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisReleaseRepository implements ReleaseRepository {

    private final ReleaseMapper mapper;

    public MyBatisReleaseRepository(ReleaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ReleaseSnapshot create(long id, long projectId, String locale, String format,
                                  String content, String manifest,
                                  Long rollbackOfReleaseId, String checksum, long actorId,
                                  Instant createdAt) {
        if (mapper.lockActiveProject(projectId) == null) {
            throw new IllegalStateException("Project is not active");
        }
        long version = mapper.nextVersion(projectId, locale, format);
        mapper.insertSnapshot(id, projectId, locale, format, version, rollbackOfReleaseId,
                content, manifest, checksum, actorId, createdAt);
        return new ReleaseSnapshot(id, projectId, locale, format, version, rollbackOfReleaseId, content, manifest,
                checksum, actorId, createdAt);
    }

    @Override
    public Optional<ReleaseSnapshot> find(long projectId, long releaseId) {
        return Optional.ofNullable(mapper.findSnapshot(projectId, releaseId)).map(this::snapshot);
    }

    @Override
    public List<ReleaseSnapshot> list(long projectId, String locale, String format, int limit) {
        return mapper.findSnapshots(projectId, locale, format, limit).stream().map(this::snapshot).toList();
    }

    public List<ReleaseLine> lines(long projectId, String locale) {
        return mapper.findLines(projectId, locale).stream()
                .map(row -> new ReleaseLine(row.getEntryId(), row.getEntryKey(), row.getSourceText(),
                        row.getSourceRevision(), row.getTranslation(), row.getTranslationStatus(),
                        row.getTranslationSourceRevision(), row.getTranslationVersion())).toList();
    }

    private ReleaseSnapshot snapshot(ReleaseMapper.ReleaseRow row) {
        return new ReleaseSnapshot(row.getId(), row.getProjectId(), row.getLocale(), row.getFormat(),
                row.getVersionNo(), row.getRollbackOfReleaseId(), row.getContent(),
                row.getManifestJson(), row.getChecksumSha256(),
                row.getPublishedBy(), row.getPublishedAt());
    }
}
