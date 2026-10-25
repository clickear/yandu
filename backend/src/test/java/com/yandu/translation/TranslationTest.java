package com.yandu.translation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.audit.AuditLog;
import com.yandu.translation.application.TranslationRepository;
import com.yandu.translation.domain.Translation;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

class TranslationTest {

    private CatalogEntryApplicationService catalog;
    private TranslationRepository repository;
    private WorkspaceApplicationService workspace;
    private TranslationApplicationService service;

    @BeforeEach
    void setUpQualityService() {
        catalog = mock(CatalogEntryApplicationService.class);
        repository = mock(TranslationRepository.class);
        workspace = mock(WorkspaceApplicationService.class);
        var access = mock(ProjectAccessPolicy.class);
        given(workspace.getProject(41L, 10L)).willReturn(new WorkspaceApplicationService.ProjectView(
                "10", "41", "Store", "zh-CN", java.util.Set.of("en-US"), 1, "11"));
        given(catalog.get(41L, 10L, 20L)).willReturn(new CatalogEntryApplicationService.EntryView(
                "20", "10", "home.greeting", "Hello {user}", 3, 3, false));
        given(repository.find(10L, 20L, "en-US")).willReturn(Optional.of(
                Translation.restore(30L, 10L, 20L, "en-US", "Hello {name}", 3,
                        Translation.Status.DRAFT, 1, 41L, 41L)));
        service = new TranslationApplicationService(repository, workspace, catalog, access,
                () -> 1L, Clock.systemUTC(), mock(AuditLog.class));
    }

    @Test
    void savingCurrentSourceCreatesDraftAndIncrementsVersion() {
        var translation = Translation.newDraft(301L, 10L, 20L, "en-US");

        var changed = translation.save(0, 3, 3, "Sign in", 41L);

        assertTrue(changed);
        assertEquals("Sign in", translation.value());
        assertEquals(3, translation.basedOnSourceRevision());
        assertEquals(Translation.Status.DRAFT, translation.status());
        assertEquals(1, translation.version());
    }

    @Test
    void staleWriteAndStaleSourceCannotBeSubmittedForReview() {
        var translation = Translation.newDraft(301L, 10L, 20L, "en-US");
        translation.save(0, 2, 3, "Sign in", 41L);

        assertThrows(Translation.VersionConflictException.class,
                () -> translation.save(0, 3, 3, "Log in", 42L));
        assertThrows(IllegalStateException.class,
                () -> translation.submitForReview(1, 3, 42L));
        assertEquals(Translation.Status.NEEDS_REVIEW, translation.status());
    }

    @Test
    void sourceChangeMarksExistingTranslationNeedsReviewOnlyOnce() {
        var translation = Translation.newDraft(301L, 10L, 20L, "en-US");
        translation.save(0, 3, 3, "Sign in", 41L);

        assertTrue(translation.markNeedsReview(4, 99L));
        assertFalse(translation.markNeedsReview(4, 99L));
        assertEquals(Translation.Status.NEEDS_REVIEW, translation.status());
        assertEquals(3, translation.basedOnSourceRevision());
        assertEquals(2, translation.version());
    }

    @Test
    void reviewerCannotApproveTheirOwnTranslation() {
        var translation = Translation.newDraft(301L, 10L, 20L, "en-US");
        translation.save(0, 3, 3, "Sign in", 41L);
        translation.submitForReview(1, 3, 41L);

        assertThrows(IllegalStateException.class,
                () -> translation.decide(2, 3, 41L, Translation.Decision.APPROVE, null));
        translation.decide(2, 3, 42L, Translation.Decision.APPROVE, null);
        assertEquals(Translation.Status.APPROVED, translation.status());
        assertEquals(3, translation.version());
    }

    @Test
    void requestChangesRequiresAReasonAndRetainsTheSubmittedRevision() {
        var translation = Translation.newDraft(301L, 10L, 20L, "en-US");
        translation.save(0, 3, 3, "Sign in", 41L);
        translation.submitForReview(1, 3, 41L);

        assertThrows(IllegalArgumentException.class,
                () -> translation.decide(2, 3, 42L, Translation.Decision.REQUEST_CHANGES, " "));
        translation.decide(2, 3, 42L, Translation.Decision.REQUEST_CHANGES, "Check terminology");
        assertEquals(Translation.Status.CHANGES_REQUESTED, translation.status());
    }

    @Test
    void qualityCheckReportsNamedPlaceholderMismatchWithEntryAndLocale() {
        var issues = service.quality(41L, 10L, 20L, "en-US");

        assertEquals(1, issues.size());
        assertEquals("PLACEHOLDER_MISMATCH", issues.getFirst().ruleId());
        assertEquals(20L, issues.getFirst().entryId());
        assertEquals("en-US", issues.getFirst().locale());
        assertEquals(java.util.Map.of("user", 1), issues.getFirst().expected());
        assertEquals(java.util.Map.of("name", 1), issues.getFirst().actual());
    }

    @Test
    void qualityCheckRejectsBlankRequiredTranslation() {
        given(repository.find(10L, 20L, "en-US")).willReturn(Optional.of(
                Translation.restore(30L, 10L, 20L, "en-US", "  ", 3,
                        Translation.Status.DRAFT, 1, 41L, 41L)));

        var issues = service.quality(41L, 10L, 20L, "en-US");

        assertEquals(1, issues.size());
        assertEquals("TRANSLATION_REQUIRED", issues.getFirst().ruleId());
        assertEquals("ERROR", issues.getFirst().severity());
    }

    @Test
    void suggestionsReturnOnlyRepositoryMatchesForTheAuthorizedProjectAndLocale() {
        given(repository.findApprovedMatches(10L, 20L, "en-US", "Hello {user}", 10))
                .willReturn(List.of(new TranslationRepository.MemoryMatch(21L, "home.welcome",
                        "Hello {user}", "Welcome {user}", "en-US", 4)));

        var suggestions = service.suggestions(41L, 10L, 20L, "en-US");

        assertEquals(1, suggestions.memory().size());
        assertEquals("21", suggestions.memory().getFirst().entryId());
        assertEquals("Welcome {user}", suggestions.memory().getFirst().translation());
        assertEquals(4, suggestions.memory().getFirst().translationVersion());
    }
}
