package com.yandu.catalog.application;

import com.yandu.catalog.domain.CatalogEntry;
import com.yandu.catalog.domain.CatalogEntry.SourceChange;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** Persistence port owned by the catalog application layer. */
public interface CatalogEntryRepository {

    Optional<CatalogEntry> findForSourceEdit(long projectId, long entryId);

    Optional<CatalogEntry> findForRead(long projectId, long entryId);

    List<CatalogEntry> search(
            long projectId, long namespaceId, String keyword, int offset, int limit);

    long count(long projectId, long namespaceId, String keyword);

    void insertTerm(Term term, long actorId, Instant occurredAt);

    List<Term> listTerms(long projectId, String sourceLocale, String targetLocale,
                         int offset, int limit);

    long countTerms(long projectId, String sourceLocale, String targetLocale);

    List<Term> matchingTerms(long projectId, String sourceLocale, String targetLocale,
                             String sourceText, int limit);

    boolean archiveTerm(long projectId, long termId, Instant occurredAt);

    List<RevisionSnapshot> history(long projectId, long entryId);

    boolean archive(long projectId, long entryId, long actorId, Instant occurredAt);

    void insert(
            long namespaceId,
            String sourceLocale,
            CatalogEntry entry,
            long actorId,
            Instant occurredAt);

    void persistSourceChange(
            CatalogEntry entry,
            SourceChange change,
            long actorId,
            Instant occurredAt);

    record RevisionSnapshot(
            long id, long entryId, long sourceRevision, String sourceText,
            long changedBy, Instant createdAt) {
    }

    record Term(long id, long projectId, String sourceLocale, String targetLocale,
                String sourceTerm, String targetTerm, String note, Instant createdAt) { }
}
