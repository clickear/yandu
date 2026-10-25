package com.yandu.catalog;

import com.yandu.audit.AuditLog;
import com.yandu.catalog.application.CatalogEntryRepository;
import com.yandu.catalog.domain.CatalogEntry;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

/** Coordinates catalog use cases without exposing persistence or web types to the domain. */
@Service
public class CatalogEntryApplicationService {

    private final CatalogEntryRepository entries;
    private final ProjectAccessPolicy accessPolicy;
    private final WorkspaceApplicationService workspace;
    private final LongSupplier ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditLog audit;

    public CatalogEntryApplicationService(
            CatalogEntryRepository entries,
            ProjectAccessPolicy accessPolicy,
            WorkspaceApplicationService workspace,
            LongSupplier ids,
            Clock clock,
            ApplicationEventPublisher events,
            AuditLog audit) {
        this.entries = Objects.requireNonNull(entries, "entries");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Transactional
    public EntryView create(CreateEntry command) {
        accessPolicy.requireCanManageCatalog(command.actorId(), command.projectId());
        var now = clock.instant();
        var entryId = ids.getAsLong();
        var entry = new CatalogEntry(
                entryId, command.projectId(), command.key(), command.sourceText(), 1, 1);
        entries.insert(command.namespaceId(), command.sourceLocale(),
                entry, command.actorId(), now);
        audit.record(command.projectId(), command.actorId(), "CATALOG_ENTRY_CREATED",
                "CATALOG_ENTRY", entryId, java.util.Map.of("namespaceId", command.namespaceId(),
                        "key", entry.key(), "sourceRevision", entry.sourceRevision()));
        return EntryView.from(entry, false);
    }

    @Transactional
    public EntryView changeSource(ChangeSource command) {
        accessPolicy.requireCanManageCatalog(command.actorId(), command.projectId());
        var entry = entries.findForSourceEdit(
                        command.projectId(), command.entryId())
                .orElseThrow(() -> new CatalogEntryNotFoundException(command.entryId()));

        CatalogEntry.SourceChange change;
        try {
            change = entry.changeSource(command.expectedSourceRevision(), command.sourceText());
        } catch (CatalogEntry.SourceRevisionConflictException conflict) {
            throw new SourceRevisionConflictException(
                    conflict.expectedRevision(), conflict.actualRevision());
        }
        if (change.changed()) {
            var occurredAt = clock.instant();
            entries.persistSourceChange(entry, change, command.actorId(), occurredAt);
            events.publishEvent(new SourceChanged(command.projectId(), command.entryId(),
                    change.currentRevision(), command.actorId(), occurredAt));
            audit.record(command.projectId(), command.actorId(), "SOURCE_CHANGED",
                    "CATALOG_ENTRY", command.entryId(), java.util.Map.of(
                            "sourceRevision", change.currentRevision(), "key", entry.key()));
        }
        return EntryView.from(entry, change.changed());
    }

    @Transactional(readOnly = true)
    public long currentSourceRevision(long actorId, long projectId, long entryId) {
        accessPolicy.requireCanReadCatalog(actorId, projectId);
        return entries.findForRead(projectId, entryId)
                .orElseThrow(() -> new CatalogEntryNotFoundException(entryId))
                .sourceRevision();
    }

    @Transactional(readOnly = true)
    public EntryView get(long actorId, long projectId, long entryId) {
        accessPolicy.requireCanReadCatalog(actorId, projectId);
        return entries.findForRead(projectId, entryId)
                .map(entry -> EntryView.from(entry, false))
                .orElseThrow(() -> new CatalogEntryNotFoundException(entryId));
    }

    @Transactional(readOnly = true)
    public EntryPage search(SearchEntries query) {
        accessPolicy.requireCanReadCatalog(query.actorId(), query.projectId());
        int offset = Math.multiplyExact(query.page() - 1, query.pageSize());
        var entriesPage = entries.search(query.projectId(), query.namespaceId(),
                query.keyword(), offset, query.pageSize());
        var total = entries.count(query.projectId(), query.namespaceId(), query.keyword());
        return new EntryPage(query.page(), query.pageSize(), total,
                entriesPage.stream().map(entry -> EntryView.from(entry, false)).toList());
    }

    @Transactional(readOnly = true)
    public List<RevisionView> history(ReadHistory query) {
        accessPolicy.requireCanReadCatalog(query.actorId(), query.projectId());
        entries.findForRead(query.projectId(), query.entryId())
                .orElseThrow(() -> new CatalogEntryNotFoundException(query.entryId()));
        return entries.history(query.projectId(), query.entryId()).stream()
                .map(revision -> new RevisionView(Long.toString(revision.id()),
                        revision.sourceRevision(), revision.sourceText(),
                        Long.toString(revision.changedBy()), revision.createdAt()))
                .toList();
    }

    @Transactional
    public void archive(ArchiveEntry command) {
        accessPolicy.requireCanManageCatalog(command.actorId(), command.projectId());
        if (!entries.archive(command.projectId(), command.entryId(),
                command.actorId(), clock.instant())) {
            throw new CatalogEntryNotFoundException(command.entryId());
        }
        audit.record(command.projectId(), command.actorId(), "CATALOG_ENTRY_ARCHIVED",
                "CATALOG_ENTRY", command.entryId(), java.util.Map.of());
    }

    @Transactional
    public TermView addTerm(AddTerm command) {
        accessPolicy.requireCanManageProject(command.actorId(), command.projectId());
        var project = workspace.getProject(command.actorId(), command.projectId());
        var sourceLocale = WorkspaceApplicationService.normalizeLocale(command.sourceLocale());
        var targetLocale = WorkspaceApplicationService.normalizeLocale(command.targetLocale());
        requireTermLocales(project, sourceLocale, targetLocale);
        var sourceTerm = requiredTerm(command.sourceTerm(), 200, "sourceTerm");
        var targetTerm = requiredTerm(command.targetTerm(), 500, "targetTerm");
        var note = command.note() == null ? null : command.note().trim();
        if (note != null && note.length() > 1000) throw new IllegalArgumentException("note is too long");
        var term = new CatalogEntryRepository.Term(ids.getAsLong(), command.projectId(),
                sourceLocale, targetLocale, sourceTerm, targetTerm, note, clock.instant());
        entries.insertTerm(term, command.actorId(), term.createdAt());
        audit.record(command.projectId(), command.actorId(), "GLOSSARY_TERM_CREATED",
                "GLOSSARY_TERM", term.id(), java.util.Map.of(
                        "sourceLocale", sourceLocale, "targetLocale", targetLocale));
        return termView(term);
    }

    @Transactional(readOnly = true)
    public TermPage listTerms(ReadTerms query) {
        accessPolicy.requireCanReadCatalog(query.actorId(), query.projectId());
        var project = workspace.getProject(query.actorId(), query.projectId());
        var sourceLocale = WorkspaceApplicationService.normalizeLocale(query.sourceLocale());
        var targetLocale = WorkspaceApplicationService.normalizeLocale(query.targetLocale());
        requireTermLocales(project, sourceLocale, targetLocale);
        var offset = Math.multiplyExact(query.page() - 1, query.pageSize());
        var terms = entries.listTerms(query.projectId(), sourceLocale, targetLocale,
                offset, query.pageSize()).stream().map(CatalogEntryApplicationService::termView).toList();
        return new TermPage(query.page(), query.pageSize(),
                entries.countTerms(query.projectId(), sourceLocale, targetLocale), terms);
    }

    @Transactional
    public void archiveTerm(ArchiveTerm command) {
        accessPolicy.requireCanManageProject(command.actorId(), command.projectId());
        if (!entries.archiveTerm(command.projectId(), command.termId(), clock.instant())) {
            throw new CatalogEntryNotFoundException(command.termId());
        }
        audit.record(command.projectId(), command.actorId(), "GLOSSARY_TERM_ARCHIVED",
                "GLOSSARY_TERM", command.termId(), java.util.Map.of());
    }

    @Transactional(readOnly = true)
    public List<TermView> matchingTerms(long actorId, long projectId, String sourceLocale,
                                        String targetLocale, String sourceText, int limit) {
        accessPolicy.requireCanReadCatalog(actorId, projectId);
        if (sourceText == null || sourceText.isBlank() || limit < 1 || limit > 20) return List.of();
        var project = workspace.getProject(actorId, projectId);
        var source = WorkspaceApplicationService.normalizeLocale(sourceLocale);
        var target = WorkspaceApplicationService.normalizeLocale(targetLocale);
        requireTermLocales(project, source, target);
        return entries.matchingTerms(projectId, source, target, sourceText, limit).stream()
                .map(CatalogEntryApplicationService::termView).toList();
    }

    private static void requireTermLocales(WorkspaceApplicationService.ProjectView project,
                                          String sourceLocale, String targetLocale) {
        if (!project.sourceLocale().equals(sourceLocale)
                || !project.targetLocales().contains(targetLocale)) {
            throw new IllegalArgumentException("Glossary languages must match the project's source and target languages");
        }
    }

    private static String requiredTerm(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + maxLength + " characters");
        }
        return value.trim();
    }

    private static TermView termView(CatalogEntryRepository.Term term) {
        return new TermView(Long.toString(term.id()), Long.toString(term.projectId()),
                term.sourceLocale(), term.targetLocale(), term.sourceTerm(), term.targetTerm(),
                term.note(), term.createdAt());
    }

    public record AddTerm(long actorId, long projectId, String sourceLocale, String targetLocale,
                          String sourceTerm, String targetTerm, String note) {
        public AddTerm {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
        }
    }

    public record ReadTerms(long actorId, long projectId, String sourceLocale, String targetLocale,
                            int page, int pageSize) {
        public ReadTerms {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("page must be positive and pageSize must be between 1 and 100");
            }
        }
    }

    public record ArchiveTerm(long actorId, long projectId, long termId) {
        public ArchiveTerm {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(termId, "termId");
        }
    }

    public record TermView(String id, String projectId, String sourceLocale, String targetLocale,
                           String sourceTerm, String targetTerm, String note,
                           java.time.Instant createdAt) { }

    public record TermPage(int page, int pageSize, long total, List<TermView> items) { }

    public record SearchEntries(long actorId, long projectId, long namespaceId,
                                String keyword, int page, int pageSize) {
        public SearchEntries {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(namespaceId, "namespaceId");
            if (page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("page must be positive and pageSize must be between 1 and 100");
            }
            if (keyword != null && keyword.isBlank()) {
                keyword = null;
            }
        }
    }

    public record ReadHistory(long actorId, long projectId, long entryId) {
        public ReadHistory {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(entryId, "entryId");
        }
    }

    public record ArchiveEntry(long actorId, long projectId, long entryId) {
        public ArchiveEntry {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(entryId, "entryId");
        }
    }

    public record EntryPage(int page, int pageSize, long total, List<EntryView> items) {
    }

    public record RevisionView(String id, long sourceRevision, String sourceText,
                               String changedBy, java.time.Instant createdAt) {
    }

    public record CreateEntry(
            long actorId,
            long projectId,
            long namespaceId,
            String sourceLocale,
            String key,
            String sourceText) {
        public CreateEntry {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(namespaceId, "namespaceId");
            if (sourceLocale == null || sourceLocale.isBlank()) {
                throw new IllegalArgumentException("sourceLocale must not be blank");
            }
        }
    }

    public record ChangeSource(
            long actorId,
            long projectId,
            long entryId,
            long expectedSourceRevision,
            String sourceText) {
        public ChangeSource {
            requirePositive(actorId, "actorId");
            requirePositive(projectId, "projectId");
            requirePositive(entryId, "entryId");
            if (expectedSourceRevision < 1) {
                throw new IllegalArgumentException("expectedSourceRevision must be positive");
            }
        }
    }

    public record SourceChanged(long projectId, long entryId, long sourceRevision,
                                long actorId, java.time.Instant occurredAt) {
    }

    public record EntryView(String id, String projectId, String key, String sourceText,
                            long sourceRevision, long version, boolean changed) {
        private static EntryView from(CatalogEntry entry, boolean changed) {
            // Snowflake IDs exceed JavaScript's exact integer range, so the API boundary uses strings.
            return new EntryView(Long.toString(entry.id()), Long.toString(entry.projectId()),
                    entry.key(), entry.sourceText(),
                    entry.sourceRevision(), entry.version(), changed);
        }
    }

    public static final class CatalogEntryNotFoundException extends RuntimeException {
        public CatalogEntryNotFoundException(long entryId) {
            super("Catalog entry not found: " + entryId);
        }
    }

    public static final class SourceRevisionConflictException extends RuntimeException {
        private final long expectedRevision;
        private final long actualRevision;

        public SourceRevisionConflictException(long expectedRevision, long actualRevision) {
            super("Expected source revision " + expectedRevision + " but found " + actualRevision);
            this.expectedRevision = expectedRevision;
            this.actualRevision = actualRevision;
        }

        public long expectedRevision() { return expectedRevision; }
        public long actualRevision() { return actualRevision; }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
