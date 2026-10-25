package com.yandu.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.yandu.catalog.domain.CatalogEntry;
import com.yandu.audit.AuditLog;
import com.yandu.catalog.domain.CatalogEntry.SourceChange;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.catalog.CatalogEntryApplicationService.SourceRevisionConflictException;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class CatalogEntryApplicationServiceTest {

    private static final long ACTOR = 11L;
    private static final long PROJECT = 22L;
    private static final long NAMESPACE = 33L;
    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Test
    void createChecksAuthorizationBeforePersistingTheEntry() {
        var repository = new InMemoryCatalogEntryRepository();
        var authorizer = new RecordingAccessPolicy();
        var service = service(repository, authorizer);

        var result = service.create(new CatalogEntryApplicationService.CreateEntry(
                ACTOR, PROJECT, NAMESPACE, "en-US", "auth.login", "Sign in"));

        assertEquals(1, authorizer.checks);
        assertEquals(PROJECT, authorizer.projectId);
        assertEquals("Sign in", result.sourceText());
        assertEquals(1, result.sourceRevision());
        assertEquals(1, result.version());
        assertEquals(1, repository.created.size());
        assertEquals("101", result.id());
        assertEquals(1, repository.persistedRevisions.size());
    }

    @Test
    void sourceEditPersistsOneNewRevisionAndReturnsUpdatedState() {
        var repository = existingEntry();
        var service = service(repository, new RecordingAccessPolicy());

        var result = service.changeSource(new CatalogEntryApplicationService.ChangeSource(
                ACTOR, PROJECT, 101L, 1, "Log in"));

        assertTrue(result.changed());
        assertEquals("Log in", result.sourceText());
        assertEquals(2, result.sourceRevision());
        assertEquals(2, result.version());
        assertEquals(1, repository.persistedChanges.size());
        assertEquals(2, repository.persistedRevisions.getLast());
    }

    @Test
    void unchangedSourceDoesNotCreateAnotherRevision() {
        var repository = existingEntry();
        var service = service(repository, new RecordingAccessPolicy());

        var result = service.changeSource(new CatalogEntryApplicationService.ChangeSource(
                ACTOR, PROJECT, 101L, 1, "Sign in"));

        assertFalse(result.changed());
        assertEquals(1, result.sourceRevision());
        assertEquals(1, result.version());
        assertTrue(repository.persistedChanges.isEmpty());
        assertEquals(1, repository.persistedRevisions.size());
    }

    @Test
    void accessDenialPreventsAnyCatalogWrite() {
        var repository = new InMemoryCatalogEntryRepository();
        ProjectAccessPolicy denyAll = new ProjectAccessPolicy() {
            @Override
            public void requireCanCreateProject(long actorId) {
                throw new SecurityException("denied");
            }

            @Override
            public void requireCanManageProject(long actorId, long projectId) {
                throw new SecurityException("denied");
            }

            @Override
            public void requireCanManageMembers(long actorId, long projectId) {
                throw new SecurityException("denied");
            }

            @Override
            public void requireCanReadProject(long actorId, long projectId) {
                throw new SecurityException("denied");
            }

            @Override
            public void requireCanManageCatalog(long actorId, long projectId) {
                throw new SecurityException("denied");
            }

            @Override
            public void requireCanReadCatalog(long actorId, long projectId) {
                throw new SecurityException("denied");
            }
        };
        var service = service(repository, denyAll);

        assertThrows(SecurityException.class, () -> service.create(new CatalogEntryApplicationService.CreateEntry(
                ACTOR, PROJECT, NAMESPACE, "en-US", "auth.login", "Sign in")));

        assertTrue(repository.created.isEmpty());
        assertTrue(repository.persistedRevisions.isEmpty());
    }

    @Test
    void searchChecksProjectAccessAndReturnsABoundedPage() {
        var repository = existingEntry();
        var policy = new RecordingAccessPolicy();
        var service = service(repository, policy);

        var page = service.search(new CatalogEntryApplicationService.SearchEntries(
                ACTOR, PROJECT, NAMESPACE, "auth", 1, 20));

        assertEquals(PROJECT, policy.projectId);
        assertEquals(1, page.total());
        assertEquals(1, page.items().size());
        assertEquals("101", page.items().getFirst().id());
    }

    @Test
    void sourceHistoryChecksProjectAccessAndReturnsImmutableSnapshots() {
        var repository = existingEntry();
        repository.history.add(new CatalogEntryRepository.RevisionSnapshot(
                501L, 101L, 1, "Sign in", ACTOR, NOW));
        var policy = new RecordingAccessPolicy();
        var service = service(repository, policy);

        var revisions = service.history(new CatalogEntryApplicationService.ReadHistory(
                ACTOR, PROJECT, 101L));

        assertEquals(PROJECT, policy.projectId);
        assertEquals(1, revisions.size());
        assertEquals("Sign in", revisions.getFirst().sourceText());
    }

    @Test
    void readOnlyCatalogQueriesDoNotUseTheLockingRead() {
        var repository = existingEntry();
        repository.history.add(new CatalogEntryRepository.RevisionSnapshot(
                501L, 101L, 1, "Sign in", ACTOR, NOW));
        var service = service(repository, new RecordingAccessPolicy());

        service.currentSourceRevision(ACTOR, PROJECT, 101L);
        service.get(ACTOR, PROJECT, 101L);
        service.history(new CatalogEntryApplicationService.ReadHistory(ACTOR, PROJECT, 101L));

        assertEquals(0, repository.lockingReads);
    }

    @Test
    void archiveChecksProjectAccessAndDoesNotRemoveSourceHistory() {
        var repository = existingEntry();
        repository.history.add(new CatalogEntryRepository.RevisionSnapshot(
                501L, 101L, 1, "Sign in", ACTOR, NOW));
        var policy = new RecordingAccessPolicy();
        var service = service(repository, policy);

        service.archive(new CatalogEntryApplicationService.ArchiveEntry(ACTOR, PROJECT, 101L));

        assertEquals(PROJECT, policy.projectId);
        assertTrue(repository.archived.contains(101L));
        assertEquals(1, repository.history.size());
    }

    @Test
    void staleSourceEditDoesNotPersistARevision() {
        var repository = existingEntry();
        var service = service(repository, new RecordingAccessPolicy());

        assertThrows(SourceRevisionConflictException.class, () -> service.changeSource(
                new CatalogEntryApplicationService.ChangeSource(ACTOR, PROJECT, 101L, 2, "Log in")));

        assertTrue(repository.persistedChanges.isEmpty());
        assertEquals("Sign in", repository.entries.get(101L).sourceText());
        assertEquals(1, repository.entries.get(101L).sourceRevision());
    }

    private static CatalogEntryApplicationService service(
            CatalogEntryRepository repository, ProjectAccessPolicy policy) {
        return service(repository, policy, mock(WorkspaceApplicationService.class));
    }

    private static CatalogEntryApplicationService service(
            CatalogEntryRepository repository, ProjectAccessPolicy policy,
            WorkspaceApplicationService workspace) {
        var generatedId = new long[] {100};
        return new CatalogEntryApplicationService(repository, policy, workspace, () -> ++generatedId[0],
                Clock.fixed(NOW, ZoneOffset.UTC), event -> { }, mock(AuditLog.class));
    }

    @Test
    void glossaryTermsRequireProjectLanguagesAndAreStoredInTheAuthorizedProject() {
        var repository = new InMemoryCatalogEntryRepository();
        var authorizer = new RecordingAccessPolicy();
        var workspace = mock(WorkspaceApplicationService.class);
        given(workspace.getProject(ACTOR, PROJECT)).willReturn(new WorkspaceApplicationService.ProjectView(
                "22", "11", "Store", "en-US", java.util.Set.of("fr-FR"), 1, "22"));
        var service = service(repository, authorizer, workspace);

        var term = service.addTerm(new CatalogEntryApplicationService.AddTerm(
                ACTOR, PROJECT, "en-us", "fr-FR", "  Checkout ", " Paiement ", "  use on button "));

        assertEquals(1, authorizer.checks);
        assertEquals("Checkout", term.sourceTerm());
        assertEquals("Paiement", term.targetTerm());
        assertEquals("use on button", term.note());
        assertEquals(1, repository.terms.size());
        assertThrows(IllegalArgumentException.class, () -> service.addTerm(
                new CatalogEntryApplicationService.AddTerm(ACTOR, PROJECT,
                        "en-US", "de-DE", "Cart", "Warenkorb", null)));
    }

    private static InMemoryCatalogEntryRepository existingEntry() {
        var repository = new InMemoryCatalogEntryRepository();
        var entry = new CatalogEntry(101L, PROJECT, "auth.login", "Sign in", 1, 1);
        repository.entries.put(entry.id(), entry);
        repository.persistedRevisions.add(1L);
        return repository;
    }

    private static final class RecordingAccessPolicy implements ProjectAccessPolicy {
        private int checks;
        private long projectId;

        @Override
        public void requireCanCreateProject(long actorId) {
            checks++;
        }

        @Override
        public void requireCanManageProject(long actorId, long projectId) {
            checks++;
            this.projectId = projectId;
        }

        @Override
        public void requireCanManageMembers(long actorId, long projectId) {
            checks++;
            this.projectId = projectId;
        }

        @Override
        public void requireCanReadProject(long actorId, long projectId) {
            checks++;
            this.projectId = projectId;
        }

        @Override
        public void requireCanManageCatalog(long actorId, long projectId) {
            checks++;
            this.projectId = projectId;
        }

        @Override
        public void requireCanReadCatalog(long actorId, long projectId) {
            checks++;
            this.projectId = projectId;
        }
    }

    private static final class InMemoryCatalogEntryRepository implements CatalogEntryRepository {
        private final Map<Long, CatalogEntry> entries = new HashMap<>();
        private final ArrayList<Long> created = new ArrayList<>();
        private final ArrayList<Long> persistedRevisions = new ArrayList<>();
        private final ArrayList<SourceChange> persistedChanges = new ArrayList<>();
        private final ArrayList<RevisionSnapshot> history = new ArrayList<>();
        private final ArrayList<Long> archived = new ArrayList<>();
        private final ArrayList<Term> terms = new ArrayList<>();
        private int lockingReads;

        @Override
        public Optional<CatalogEntry> findForSourceEdit(long projectId, long entryId) {
            lockingReads++;
            return Optional.ofNullable(entries.get(entryId));
        }

        @Override
        public Optional<CatalogEntry> findForRead(long projectId, long entryId) {
            return Optional.ofNullable(entries.get(entryId));
        }

        @Override
        public List<CatalogEntry> search(long projectId, long namespaceId, String keyword,
                                         int offset, int limit) {
            return entries.values().stream()
                    .filter(entry -> entry.projectId() == projectId)
                    .filter(entry -> keyword == null || entry.key().contains(keyword))
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long count(long projectId, long namespaceId, String keyword) {
            return search(projectId, namespaceId, keyword, 0, Integer.MAX_VALUE).size();
        }

        @Override
        public void insertTerm(Term term, long actorId, Instant occurredAt) { terms.add(term); }

        @Override
        public List<Term> listTerms(long projectId, String sourceLocale, String targetLocale,
                                    int offset, int limit) {
            return terms.stream().filter(term -> term.projectId() == projectId
                    && term.sourceLocale().equals(sourceLocale)
                    && term.targetLocale().equals(targetLocale)).skip(offset).limit(limit).toList();
        }

        @Override
        public long countTerms(long projectId, String sourceLocale, String targetLocale) {
            return listTerms(projectId, sourceLocale, targetLocale, 0, Integer.MAX_VALUE).size();
        }

        @Override
        public List<Term> matchingTerms(long projectId, String sourceLocale, String targetLocale,
                                        String sourceText, int limit) {
            var lower = sourceText.toLowerCase(java.util.Locale.ROOT);
            return terms.stream().filter(term -> term.projectId() == projectId
                    && term.sourceLocale().equals(sourceLocale) && term.targetLocale().equals(targetLocale)
                    && lower.contains(term.sourceTerm().toLowerCase(java.util.Locale.ROOT)))
                    .limit(limit).toList();
        }

        @Override
        public boolean archiveTerm(long projectId, long termId, Instant occurredAt) {
            return terms.removeIf(term -> term.projectId() == projectId && term.id() == termId);
        }

        @Override
        public List<RevisionSnapshot> history(long projectId, long entryId) {
            return List.copyOf(history);
        }

        @Override
        public boolean archive(long projectId, long entryId, long actorId, Instant occurredAt) {
            return archived.add(entryId);
        }

        @Override
        public void insert(long namespaceId, String sourceLocale,
                           CatalogEntry entry, long actorId, Instant occurredAt) {
            entries.put(entry.id(), entry);
            created.add(entry.id());
            persistedRevisions.add(entry.sourceRevision());
        }

        @Override
        public void persistSourceChange(CatalogEntry entry, SourceChange change,
                                        long actorId, Instant occurredAt) {
            persistedChanges.add(change);
            persistedRevisions.add(change.currentRevision());
        }
    }
}
