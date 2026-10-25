package com.yandu.web;

import com.yandu.catalog.CatalogEntryApplicationService.CatalogEntryNotFoundException;
import com.yandu.catalog.CatalogEntryApplicationService.SourceRevisionConflictException;
import com.yandu.translation.TranslationApplicationService.TargetLocaleNotConfiguredException;
import com.yandu.translation.TranslationApplicationService.TranslationNotFoundException;
import com.yandu.translation.TranslationApplicationService.TranslationVersionConflictException;
import com.yandu.translation.TranslationApplicationService.QualityGateException;
import com.yandu.release.ReleaseApplicationService.ReleaseGateException;
import com.yandu.release.ReleaseApplicationService.ReleaseNotFoundException;
import com.yandu.imports.ImportApplicationService.IdempotencyConflictException;
import com.yandu.imports.ImportApplicationService.ImportJobNotFoundException;
import com.yandu.imports.ImportApplicationService.ImportPreviewConflictException;
import com.yandu.workspace.WorkspaceApplicationService.ProjectNotFoundException;
import com.yandu.workspace.WorkspaceApplicationService.UserNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({AccessDeniedException.class, SecurityException.class})
    ProblemDetail forbidden(RuntimeException exception) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", exception.getMessage());
    }

    @ExceptionHandler({CatalogEntryNotFoundException.class, ProjectNotFoundException.class,
            UserNotFoundException.class, TranslationNotFoundException.class,
            ReleaseNotFoundException.class, ImportJobNotFoundException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(SourceRevisionConflictException.class)
    ProblemDetail revisionConflict(SourceRevisionConflictException exception) {
        var detail = problem(HttpStatus.CONFLICT, "Source revision conflict", exception.getMessage());
        detail.setProperty("expectedRevision", exception.expectedRevision());
        detail.setProperty("actualRevision", exception.actualRevision());
        return detail;
    }

    @ExceptionHandler(TranslationVersionConflictException.class)
    ProblemDetail translationConflict(TranslationVersionConflictException exception) {
        var detail = problem(HttpStatus.CONFLICT, "Translation version conflict", exception.getMessage());
        detail.setProperty("expectedVersion", exception.expectedVersion());
        detail.setProperty("actualVersion", exception.actualVersion());
        return detail;
    }

    @ExceptionHandler({QualityGateException.class, ReleaseGateException.class})
    ProblemDetail qualityGate(RuntimeException exception) {
        var detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Quality gate failed", exception.getMessage());
        if (exception instanceof QualityGateException translationGate) {
            detail.setProperty("issues", translationGate.issues());
        } else if (exception instanceof ReleaseGateException releaseGate) {
            detail.setProperty("failures", releaseGate.failures());
        }
        return detail;
    }

    @ExceptionHandler({IllegalStateException.class, ImportPreviewConflictException.class,
            IdempotencyConflictException.class})
    ProblemDetail invalidTransition(IllegalStateException exception) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicateOrConstraintViolation(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "Resource conflicts with existing data",
                "A unique key or relationship constraint was violated");
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            TargetLocaleNotConfiguredException.class})
    ProblemDetail invalidRequest(Exception exception) {
        var detail = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .findFirst().orElse("Request validation failed")
                : exception.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setTitle(title);
        return problem;
    }
}
