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

    EntrySearch search(long projectId, long namespaceId, String keyword, int offset, int limit);

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

    record EntrySearch(List<CatalogEntry> items, long total) { }

    /** Persistence port for glossary terms, whose lifecycle is independent from source entries. */
    interface GlossaryTerms {

        void insert(Term term, long actorId, Instant occurredAt);

        TermSearch list(long projectId, String sourceLocale, String targetLocale,
                        int offset, int limit);

        List<Term> matching(long projectId, String sourceLocale, String targetLocale,
                            String sourceText, int limit);

        boolean archive(long projectId, long termId, Instant occurredAt);

        record TermSearch(List<Term> items, long total) { }

        record Term(long id, long projectId, String sourceLocale, String targetLocale,
                    String sourceTerm, String targetTerm, String note, Instant createdAt) { }
    }
}
