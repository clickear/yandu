package com.yandu.translation;

import com.yandu.audit.AuditLog;
import com.yandu.catalog.CatalogEntryApplicationService;
import com.yandu.catalog.CatalogEntryApplicationService.SourceChanged;
import com.yandu.translation.application.TranslationRepository;
import com.yandu.translation.domain.Translation;
import com.yandu.workspace.ProjectAccessPolicy;
import com.yandu.workspace.WorkspaceApplicationService;
import java.time.Clock;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.LongSupplier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates translation editing, review, and synchronous source-revision invalidation. */
@Service
public class TranslationApplicationService {

    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_.-]*)}");

    private final TranslationRepository translations;
    private final WorkspaceApplicationService workspace;
    private final CatalogEntryApplicationService catalog;
    private final ProjectAccessPolicy access;
    private final LongSupplier ids;
    private final Clock clock;
    private final AuditLog audit;

    public TranslationApplicationService(TranslationRepository translations,
                                         WorkspaceApplicationService workspace,
                                         CatalogEntryApplicationService catalog,
                                         ProjectAccessPolicy access,
                                         LongSupplier ids,
                                         Clock clock,
                                         AuditLog audit) {
        this.translations = Objects.requireNonNull(translations);
        this.workspace = Objects.requireNonNull(workspace);
        this.catalog = Objects.requireNonNull(catalog);
        this.access = Objects.requireNonNull(access);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
    }

    @Transactional
    public TranslationView save(SaveTranslation command) {
        access.requireCanManageTranslation(command.actorId(), command.projectId());
        requireTargetLocale(command.actorId(), command.projectId(), command.locale());
        var sourceRevision = catalog.currentSourceRevision(
                command.actorId(), command.projectId(), command.entryId());
        var existing = translations.find(command.projectId(), command.entryId(), command.locale());
        var translation = existing.orElseGet(() -> Translation.newDraft(ids.getAsLong(),
                command.projectId(), command.entryId(), command.locale()));
        var expectedVersion = command.expectedVersion();
        boolean changed;
        try {
            changed = translation.save(expectedVersion, command.basedOnSourceRevision(),
                    sourceRevision, command.value(), command.actorId());
        } catch (Translation.VersionConflictException conflict) {
            throw new TranslationVersionConflictException(
                    conflict.expectedVersion(), conflict.actualVersion());
        }
        if (changed) {
            var now = clock.instant();
            if (existing.isPresent()) {
                translations.update(translation, expectedVersion, now);
            } else {
                translations.insert(translation, now);
            }
            translations.appendRevision(translation, command.actorId(), now);
            audit.record(command.projectId(), command.actorId(), "TRANSLATION_SAVED",
                    "TRANSLATION", translation.id(), java.util.Map.of("entryId", command.entryId(),
                            "locale", translation.locale(), "version", translation.version(),
                            "sourceRevision", translation.basedOnSourceRevision(),
                            "status", translation.status().name()));
        }
        return view(translation, sourceRevision);
    }

    @Transactional(readOnly = true)
    public TranslationView get(ReadTranslation query) {
        access.requireCanReadTranslation(query.actorId(), query.projectId());
        requireTargetLocale(query.actorId(), query.projectId(), query.locale());
        var sourceRevision = catalog.currentSourceRevision(
                query.actorId(), query.projectId(), query.entryId());
        var translation = translations.find(query.projectId(), query.entryId(), query.locale())
                .orElseThrow(() -> new TranslationNotFoundException(
                        query.entryId(), query.locale()));
        return view(translation, sourceRevision);
    }

    @Transactional(readOnly = true)
    public WorkspacePage searchWorkspace(SearchWorkspace query) {
        access.requireCanReadTranslation(query.actorId(), query.projectId());
        var locale = Translation.normalizeLocale(query.locale());
        requireTargetLocale(query.actorId(), query.projectId(), locale);
        int offset = Math.multiplyExact(query.page() - 1, query.pageSize());
        var items = translations.searchWorkspace(query.projectId(), query.namespaceId(), locale,
                query.status(), query.keyword(), offset, query.pageSize()).stream()
                .map(entry -> new WorkspaceEntryView(Long.toString(entry.entryId()), entry.key(),
                        entry.sourceText(), entry.sourceRevision(),
                        entry.translationId() == null ? null : Long.toString(entry.translationId()),
                        entry.translation(), entry.status(), entry.translationVersion(),
                        entry.translationSourceRevision(),
                        entry.translationSourceRevision() != null
                                && entry.translationSourceRevision() < entry.sourceRevision()))
                .toList();
        return new WorkspacePage(query.page(), query.pageSize(),
                translations.countWorkspace(query.projectId(), query.namespaceId(), locale,
                        query.status(), query.keyword()),
                translations.countAllWorkspace(query.projectId(), query.namespaceId()),
                translations.countTranslated(query.projectId(), query.namespaceId(), locale),
                translations.countReviewed(query.projectId(), query.namespaceId(), locale), items);
    }

    @Transactional(readOnly = true)
    public Optional<TranslationView> findForImport(long actorId, long projectId,
                                                    long entryId, String locale) {
        access.requireCanManageTranslation(actorId, projectId);
        requireTargetLocale(actorId, projectId, locale);
        var sourceRevision = catalog.currentSourceRevision(actorId, projectId, entryId);
        return translations.find(projectId, entryId, locale)
                .map(translation -> view(translation, sourceRevision));
    }

    @Transactional
    public TranslationView submit(SubmitTranslation command) {
        access.requireCanManageTranslation(command.actorId(), command.projectId());
        requireTargetLocale(command.actorId(), command.projectId(), command.locale());
        var sourceRevision = catalog.currentSourceRevision(
                command.actorId(), command.projectId(), command.entryId());
        var translation = find(command.projectId(), command.entryId(), command.locale());
        var qualityIssues = checkQuality(catalog.get(command.actorId(), command.projectId(),
                command.entryId()).sourceText(), translation.value(), command.entryId(),
                translation.locale());
        if (!qualityIssues.isEmpty()) {
            throw new QualityGateException(qualityIssues);
        }
        var expected = translation.version();
        try {
            translation.submitForReview(command.expectedVersion(), sourceRevision, command.actorId());
        } catch (Translation.VersionConflictException conflict) {
            throw new TranslationVersionConflictException(
                    conflict.expectedVersion(), conflict.actualVersion());
        }
        var now = clock.instant();
        translations.update(translation, expected, now);
        audit.record(command.projectId(), command.actorId(), "TRANSLATION_SUBMITTED",
                "TRANSLATION", translation.id(), java.util.Map.of("entryId", command.entryId(),
                        "locale", translation.locale(), "version", translation.version()));
        return view(translation, sourceRevision);
    }

    @Transactional(readOnly = true)
    public List<QualityIssue> quality(long actorId, long projectId, long entryId, String locale) {
        access.requireCanReadTranslation(actorId, projectId);
        requireTargetLocale(actorId, projectId, locale);
        var source = catalog.get(actorId, projectId, entryId).sourceText();
        var translation = find(projectId, entryId, locale);
        return checkQuality(source, translation.value(), entryId, translation.locale());
    }

    @Transactional(readOnly = true)
    public Suggestions suggestions(long actorId, long projectId, long entryId, String locale) {
        access.requireCanReadTranslation(actorId, projectId);
        var normalizedLocale = Translation.normalizeLocale(locale);
        var project = workspace.getProject(actorId, projectId);
        if (!project.targetLocales().contains(normalizedLocale)) {
            throw new TargetLocaleNotConfiguredException(normalizedLocale);
        }
        var entry = catalog.get(actorId, projectId, entryId);
        var memory = translations.findApprovedMatches(projectId, entryId, normalizedLocale,
                entry.sourceText(), 10)
                .stream().map(match -> new MemorySuggestion(Long.toString(match.entryId()),
                        match.key(), match.sourceText(), match.translation(), match.locale(),
                        match.translationVersion())).toList();
        var terms = catalog.matchingTerms(actorId, projectId,
                project.sourceLocale(), normalizedLocale,
                entry.sourceText(), 10).stream().map(term -> new TermSuggestion(term.id(),
                term.sourceTerm(), term.targetTerm(), term.note())).toList();
        return new Suggestions(memory, terms);
    }

    private static List<QualityIssue> checkQuality(String source, String value,
                                                   long entryId, String locale) {
        var issues = new java.util.ArrayList<QualityIssue>();
        if (value == null || value.isBlank()) {
            issues.add(new QualityIssue("TRANSLATION_REQUIRED", "ERROR", entryId, locale,
                    "译文不能为空。", java.util.Map.of(), java.util.Map.of()));
        }
        if (value != null && !value.isBlank()) {
            var sourceTokens = placeholders(source);
            var translationTokens = placeholders(value);
            if (!sourceTokens.equals(translationTokens)) {
                issues.add(new QualityIssue("PLACEHOLDER_MISMATCH", "ERROR", entryId, locale,
                        "译文中的命名占位符必须与源文案一致。", sourceTokens, translationTokens));
            }
        }
        return List.copyOf(issues);
    }

    private static java.util.Map<String, Integer> placeholders(String value) {
        var tokens = new java.util.TreeMap<String, Integer>();
        Matcher matcher = NAMED_PLACEHOLDER.matcher(value);
        while (matcher.find()) tokens.merge(matcher.group(1), 1, Integer::sum);
        return tokens;
    }

    @Transactional
    public TranslationView decide(ReviewTranslation command) {
        access.requireCanReviewTranslation(command.reviewerId(), command.projectId());
        requireTargetLocale(command.reviewerId(), command.projectId(), command.locale());
        var sourceRevision = catalog.currentSourceRevision(
                command.reviewerId(), command.projectId(), command.entryId());
        var translation = find(command.projectId(), command.entryId(), command.locale());
        var expected = translation.version();
        try {
            translation.decide(command.expectedVersion(), sourceRevision, command.reviewerId(),
                    Translation.Decision.valueOf(command.decision().name()), command.reason());
        } catch (Translation.VersionConflictException conflict) {
            throw new TranslationVersionConflictException(
                    conflict.expectedVersion(), conflict.actualVersion());
        }
        var now = clock.instant();
        translations.update(translation, expected, now);
        translations.recordDecision(translation, command.expectedVersion(),
                Translation.Decision.valueOf(command.decision().name()),
                command.reviewerId(), command.reason(), now);
        audit.record(command.projectId(), command.reviewerId(), "TRANSLATION_REVIEW_DECIDED",
                "TRANSLATION", translation.id(), java.util.Map.of("entryId", command.entryId(),
                        "locale", translation.locale(), "decision", command.decision().name(),
                        "reviewedVersion", command.expectedVersion(),
                        "resultVersion", translation.version()));
        return view(translation, sourceRevision);
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void sourceChanged(SourceChanged event) {
        translations.markNeedsReview(event.projectId(), event.entryId(), event.sourceRevision(),
                event.actorId(), event.occurredAt());
    }

    private Translation find(long projectId, long entryId, String locale) {
        return translations.find(projectId, entryId, locale)
                .orElseThrow(() -> new TranslationNotFoundException(entryId, locale));
    }

    private void requireTargetLocale(long actorId, long projectId, String locale) {
        if (!workspace.getProject(actorId, projectId).targetLocales().contains(
                Translation.normalizeLocale(locale))) {
            throw new TargetLocaleNotConfiguredException(locale);
        }
    }

    private static TranslationView view(Translation translation, long sourceRevision) {
        return new TranslationView(Long.toString(translation.id()),
                Long.toString(translation.projectId()), Long.toString(translation.entryId()),
                translation.locale(), translation.value(), translation.basedOnSourceRevision(),
                translation.status(), translation.version(), sourceRevision,
                translation.basedOnSourceRevision() < sourceRevision,
                translation.translatedBy() == 0 ? null : Long.toString(translation.translatedBy()));
    }

    public record SaveTranslation(long actorId, long projectId, long entryId, String locale,
                                  long expectedVersion, long basedOnSourceRevision, String value) {
        public SaveTranslation {
            requireIds(actorId, projectId, entryId);
            if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion cannot be negative");
        }
    }

    public record ReadTranslation(long actorId, long projectId, long entryId, String locale) {
        public ReadTranslation { requireIds(actorId, projectId, entryId); }
    }

    public record SubmitTranslation(long actorId, long projectId, long entryId, String locale,
                                    long expectedVersion) {
        public SubmitTranslation {
            requireIds(actorId, projectId, entryId);
            if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
        }
    }

    public record ReviewTranslation(long reviewerId, long projectId, long entryId, String locale,
                                    long expectedVersion, ReviewDecision decision, String reason) {
        public ReviewTranslation {
            requireIds(reviewerId, projectId, entryId);
            if (expectedVersion < 1 || decision == null) {
                throw new IllegalArgumentException("expectedVersion and decision are required");
            }
        }
    }

    public enum ReviewDecision { APPROVE, REQUEST_CHANGES }

    public record TranslationView(String id, String projectId, String entryId, String locale,
                                  String value, long basedOnSourceRevision, Translation.Status status,
                                  long version, long currentSourceRevision, boolean sourceIsNewer,
                                  String translatedBy) {
    }

    public record SearchWorkspace(long actorId, long projectId, long namespaceId, String locale,
                                  String status, String keyword, int page, int pageSize) {
        public SearchWorkspace {
            requireIds(actorId, projectId, namespaceId);
            if (locale == null || locale.isBlank() || page < 1 || pageSize < 1 || pageSize > 100) {
                throw new IllegalArgumentException("locale is required; pageSize must be between 1 and 100");
            }
            if (status == null || !List.of("all", "untranslated", "draft", "reviewed").contains(status)) {
                throw new IllegalArgumentException("status must be all, untranslated, draft or reviewed");
            }
            if (keyword != null && keyword.isBlank()) keyword = null;
        }
    }

    public record WorkspaceEntryView(String entryId, String key, String sourceText,
                                     long sourceRevision, String translationId, String translation,
                                     String status, Long translationVersion,
                                     Long translationSourceRevision, boolean sourceIsNewer) { }

    public record WorkspacePage(int page, int pageSize, long total, long allTotal,
                                long translated, long reviewed,
                                 List<WorkspaceEntryView> items) { }

    public record QualityIssue(String ruleId, String severity, long entryId, String locale,
                               String message, java.util.Map<String, Integer> expected,
                               java.util.Map<String, Integer> actual) { }

    public record MemorySuggestion(String entryId, String key, String sourceText,
                                   String translation, String locale, long translationVersion) { }

    public record TermSuggestion(String id, String sourceTerm, String targetTerm, String note) { }

    public record Suggestions(List<MemorySuggestion> memory, List<TermSuggestion> terms) { }

    public static final class QualityGateException extends RuntimeException {
        private final List<QualityIssue> issues;
        public QualityGateException(List<QualityIssue> issues) {
            super("Translation has blocking quality issues");
            this.issues = List.copyOf(issues);
        }
        public List<QualityIssue> issues() { return issues; }
    }

    public static final class TranslationVersionConflictException extends RuntimeException {
        private final long expectedVersion;
        private final long actualVersion;
        public TranslationVersionConflictException(long expectedVersion, long actualVersion) {
            super("Expected translation version " + expectedVersion + " but found " + actualVersion);
            this.expectedVersion = expectedVersion;
            this.actualVersion = actualVersion;
        }
        public long expectedVersion() { return expectedVersion; }
        public long actualVersion() { return actualVersion; }
    }

    public static final class TranslationNotFoundException extends RuntimeException {
        public TranslationNotFoundException(long entryId, String locale) {
            super("Translation not found for entry " + entryId + " and locale " + locale);
        }
    }

    public static final class TargetLocaleNotConfiguredException extends RuntimeException {
        public TargetLocaleNotConfiguredException(String locale) {
            super("Target locale is not configured for this project: " + locale);
        }
    }

    private static void requireIds(long actorId, long projectId, long entryId) {
        if (actorId <= 0 || projectId <= 0 || entryId <= 0) {
            throw new IllegalArgumentException("actorId, projectId and entryId must be positive");
        }
    }
}
