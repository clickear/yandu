package com.yandu.translation.infrastructure.mysql;

import com.yandu.translation.application.TranslationRepository;
import com.yandu.translation.domain.Translation;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisTranslationRepository implements TranslationRepository {

    private final TranslationMapper mapper;
    private final LongSupplier ids;

    public MyBatisTranslationRepository(TranslationMapper mapper, LongSupplier ids) {
        this.mapper = mapper;
        this.ids = ids;
    }

    @Override
    public Optional<Translation> find(long projectId, long entryId, String locale) {
        var row = mapper.find(projectId, entryId, Translation.normalizeLocale(locale));
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public List<WorkspaceEntry> searchWorkspace(long projectId, long namespaceId, String locale,
                                                String status, String keyword, int offset, int limit) {
        return mapper.searchWorkspace(projectId, namespaceId, locale, status, keyword, offset, limit)
                .stream().map(row -> new WorkspaceEntry(row.getEntryId(), row.getEntryKey(),
                        row.getSourceText(), row.getSourceRevision(), row.getId(), row.getValue(),
                        row.getStatus(), row.getVersion(), row.getBasedOnSourceRevision()))
                .toList();
    }

    @Override
    public long countWorkspace(long projectId, long namespaceId, String locale,
                               String status, String keyword) {
        return mapper.countWorkspace(projectId, namespaceId, locale, status, keyword);
    }

    @Override
    public long countAllWorkspace(long projectId, long namespaceId) {
        return mapper.countAllWorkspace(projectId, namespaceId);
    }

    @Override
    public long countTranslated(long projectId, long namespaceId, String locale) {
        return mapper.countTranslated(projectId, namespaceId, locale);
    }

    @Override
    public long countReviewed(long projectId, long namespaceId, String locale) {
        return mapper.countReviewed(projectId, namespaceId, locale);
    }

    @Override
    public List<MemoryMatch> findApprovedMatches(long projectId, long excludedEntryId,
                                                  String locale, String sourceText, int limit) {
        return mapper.findApprovedMatches(projectId, excludedEntryId, locale, sourceText, limit)
                .stream().map(row -> new MemoryMatch(row.getEntryId(), row.getEntryKey(),
                        row.getSourceText(), row.getValue(), row.getTargetLocale(), row.getVersion()))
                .toList();
    }

    @Override
    public void insert(Translation translation, Instant occurredAt) {
        mapper.insert(toRow(translation, occurredAt));
    }

    @Override
    public void update(Translation translation, long expectedVersion, Instant occurredAt) {
        var updated = mapper.updateOptimistically(translation.id(), translation.projectId(),
                translation.entryId(), translation.locale(), expectedVersion, translation.version(),
                translation.basedOnSourceRevision(), translation.value(), translation.status().name(),
                translation.translatedBy(), translation.updatedBy(), occurredAt);
        if (updated != 1) {
            throw new IllegalStateException("Translation changed concurrently; reload and retry");
        }
    }

    @Override
    public void appendRevision(Translation translation, long actorId, Instant occurredAt) {
        mapper.insertRevision(ids.getAsLong(), translation.projectId(), translation.id(),
                translation.entryId(), translation.locale(), translation.version(),
                translation.value(), translation.basedOnSourceRevision(), actorId, occurredAt);
    }

    @Override
    public void recordDecision(Translation translation, long decidedVersion,
                               Translation.Decision decision, long reviewerId, String reason,
                               Instant occurredAt) {
        mapper.insertDecision(ids.getAsLong(), translation.projectId(), translation.id(),
                translation.entryId(), translation.locale(), decidedVersion,
                translation.basedOnSourceRevision(), decision.name(), reason, reviewerId, occurredAt);
    }

    @Override
    public void markNeedsReview(long projectId, long entryId, long sourceRevision,
                                long sourceEditorId, Instant occurredAt) {
        mapper.markNeedsReview(projectId, entryId, sourceRevision, sourceEditorId, occurredAt);
    }

    private Translation toDomain(TranslationMapper.TranslationRow row) {
        return Translation.restore(row.getId(), row.getProjectId(), row.getEntryId(),
                row.getTargetLocale(), row.getValue(), row.getBasedOnSourceRevision(),
                Translation.Status.valueOf(row.getStatus()), row.getVersion(),
                row.getTranslatedBy(), row.getUpdatedBy());
    }

    private TranslationMapper.TranslationRow toRow(Translation translation, Instant occurredAt) {
        var row = new TranslationMapper.TranslationRow();
        row.setId(translation.id());
        row.setProjectId(translation.projectId());
        row.setEntryId(translation.entryId());
        row.setTargetLocale(translation.locale());
        row.setValue(translation.value());
        row.setBasedOnSourceRevision(translation.basedOnSourceRevision());
        row.setStatus(translation.status().name());
        row.setVersion(translation.version());
        row.setTranslatedBy(translation.translatedBy());
        row.setUpdatedBy(translation.updatedBy());
        row.setCreatedAt(occurredAt);
        row.setUpdatedAt(occurredAt);
        return row;
    }
}
