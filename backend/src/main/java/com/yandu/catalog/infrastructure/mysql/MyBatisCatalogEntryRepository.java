package com.yandu.catalog.infrastructure.mysql;

import com.yandu.catalog.application.CatalogEntryRepository;
import com.yandu.catalog.domain.CatalogEntry;
import com.yandu.catalog.domain.CatalogEntry.SourceChange;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCatalogEntryRepository implements CatalogEntryRepository {

    private final CatalogEntryMapper entries;
    private final SourceRevisionMapper revisions;
    private final LongSupplier ids;

    public MyBatisCatalogEntryRepository(
            CatalogEntryMapper entries,
            SourceRevisionMapper revisions,
            LongSupplier ids) {
        this.entries = entries;
        this.revisions = revisions;
        this.ids = ids;
    }

    @Override
    public Optional<CatalogEntry> findForSourceEdit(long projectId, long entryId) {
        var row = entries.findActiveForUpdate(projectId, entryId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public Optional<CatalogEntry> findForRead(long projectId, long entryId) {
        var row = entries.findActive(projectId, entryId);
        return Optional.ofNullable(row).map(this::toDomain);
    }

    @Override
    public EntrySearch search(long projectId, long namespaceId, String keyword,
                              int offset, int limit) {
        var items = entries.searchActive(projectId, namespaceId, keyword, offset, limit).stream()
                .map(this::toDomain).toList();
        return new EntrySearch(items, entries.countActive(projectId, namespaceId, keyword));
    }

    @Override
    public void insertTerm(Term term, long actorId, Instant occurredAt) {
        entries.insertTerm(term.id(), term.projectId(), term.sourceLocale(), term.targetLocale(),
                term.sourceTerm(), term.sourceTerm().toLowerCase(java.util.Locale.ROOT),
                term.targetTerm(), term.note(), actorId, occurredAt);
    }

    @Override
    public TermSearch listTerms(long projectId, String sourceLocale, String targetLocale,
                                int offset, int limit) {
        var items = entries.listTerms(projectId, sourceLocale, targetLocale, offset, limit).stream()
                .map(this::toTerm).toList();
        return new TermSearch(items, entries.countTerms(projectId, sourceLocale, targetLocale));
    }

    @Override
    public List<Term> matchingTerms(long projectId, String sourceLocale, String targetLocale,
                                    String sourceText, int limit) {
        return entries.matchingTerms(projectId, sourceLocale, targetLocale, sourceText, limit)
                .stream().map(this::toTerm).toList();
    }

    @Override
    public boolean archiveTerm(long projectId, long termId, Instant occurredAt) {
        return entries.archiveTerm(projectId, termId, occurredAt) == 1;
    }

    @Override
    public List<RevisionSnapshot> history(long projectId, long entryId) {
        return revisions.findHistory(projectId, entryId).stream()
                .map(row -> new RevisionSnapshot(row.getId(), row.getEntryId(),
                        row.getSourceRevision(), row.getSourceText(), row.getChangedBy(),
                        row.getCreatedAt()))
                .toList();
    }

    @Override
    public boolean archive(long projectId, long entryId, long actorId, Instant occurredAt) {
        return entries.archive(projectId, entryId, actorId, occurredAt) == 1;
    }

    @Override
    public void insert(long namespaceId, String sourceLocale,
                       CatalogEntry entry, long actorId, Instant occurredAt) {
        var row = new CatalogEntryMapper.CatalogEntryRow();
        row.setId(entry.id());
        row.setProjectId(entry.projectId());
        row.setNamespaceId(namespaceId);
        row.setSourceLocale(sourceLocale);
        row.setEntryKey(entry.key());
        row.setSourceText(entry.sourceText());
        row.setSourceRevision(entry.sourceRevision());
        row.setVersion(1);
        row.setStatus("ACTIVE");
        row.setCreatedBy(actorId);
        row.setUpdatedBy(actorId);
        row.setCreatedAt(occurredAt);
        row.setUpdatedAt(occurredAt);
        entries.insert(row);
        insertRevision(entry.projectId(), entry.id(), entry.sourceRevision(),
                entry.sourceText(), actorId, occurredAt);
    }

    @Override
    public void persistSourceChange(CatalogEntry entry, SourceChange change,
                                    long actorId, Instant occurredAt) {
        var updated = entries.updateSourceOptimistically(
                entry.projectId(), entry.id(), change.previousRevision(),
                change.previousVersion(), change.currentRevision(), change.currentVersion(),
                entry.sourceText(), actorId, occurredAt);
        if (updated != 1) {
            throw new IllegalStateException("Catalog entry changed despite a source-edit row lock");
        }
        insertRevision(entry.projectId(), entry.id(), change.currentRevision(),
                entry.sourceText(), actorId, occurredAt);
    }

    private void insertRevision(long projectId, long entryId, long revision,
                                String sourceText, long actorId, Instant occurredAt) {
        var row = new SourceRevisionMapper.SourceRevisionRow();
        row.setId(ids.getAsLong());
        row.setProjectId(projectId);
        row.setEntryId(entryId);
        row.setSourceRevision(revision);
        row.setSourceText(sourceText);
        row.setChangedBy(actorId);
        row.setCreatedAt(occurredAt);
        revisions.insert(row);
    }

    private CatalogEntry toDomain(CatalogEntryMapper.CatalogEntryRow row) {
        return new CatalogEntry(row.getId(), row.getProjectId(), row.getEntryKey(),
                row.getSourceText(), row.getSourceRevision(), row.getVersion());
    }

    private Term toTerm(CatalogEntryMapper.GlossaryTermRow row) {
        return new Term(row.getId(), row.getProjectId(), row.getSourceLocale(),
                row.getTargetLocale(), row.getSourceTerm(), row.getTargetTerm(),
                row.getNote(), row.getCreatedAt());
    }
}
