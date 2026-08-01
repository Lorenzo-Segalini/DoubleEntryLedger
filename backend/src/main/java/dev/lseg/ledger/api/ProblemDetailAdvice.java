package dev.lseg.ledger.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.lseg.ledger.domain.LedgerError;
import dev.lseg.ledger.domain.LedgerException;
import dev.lseg.ledger.security.AuditService;
import dev.lseg.ledger.security.AuthenticationService;

/**
 * Turns failures into RFC 9457 {@code application/problem+json}.
 *
 * <p>Every response carries the {@code requestId}, so a caller reporting "my
 * request failed" hands over the one token that finds the request in the logs,
 * in the traces, and — if it got far enough to post — on the journal row itself.
 *
 * <p>The mapping from {@link LedgerError} to status lives on the enum rather than
 * here, so adding a way to fail without deciding how callers see it is a compile
 * error rather than an accidental 500.
 */
@RestControllerAdvice
class ProblemDetailAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);
    private static final String PROBLEM_BASE = "https://ledger.lseg.dev/problems/";

    private final CurrentPrincipal principal;
    private final AuditService audit;

    ProblemDetailAdvice(CurrentPrincipal principal, AuditService audit) {
        this.principal = principal;
        this.audit = audit;
    }

    @ExceptionHandler(LedgerException.class)
    ProblemDetail onLedgerException(LedgerException e, HttpServletRequest request) {
        LedgerError error = e.error();
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(error.httpStatus()), e.getMessage());

        problem.setType(URI.create(PROBLEM_BASE + error.problemType()));
        problem.setTitle(titleOf(error));
        problem.setProperty("code", error.name());
        problem.setProperty("requestId", requestId(request));
        if (!e.details().isEmpty()) {
            problem.setProperty("details", e.details());
        }
        return problem;
    }

    /**
     * A duplicate that got past the idempotency store and was caught by the
     * journal's own unique index — the second line of defence described in
     * docs/04-idempotency.md §4.6. Reported as a conflict rather than a 500,
     * because from the caller's point of view it is exactly that.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail onDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        boolean idempotencyClash = String.valueOf(e.getMessage()).contains("entry_idempotency_key_idx");

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                idempotencyClash
                        ? "this idempotency key has already been used to post an entry"
                        : "the request conflicts with an existing record");
        problem.setType(URI.create(PROBLEM_BASE + (idempotencyClash ? "idempotency-key-conflict" : "conflict")));
        problem.setTitle("Conflict");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    /**
     * Every authentication failure looks the same from outside: {@code 401}, no
     * detail. "No such user" and "wrong password" are an account enumeration
     * oracle if a caller can tell them apart. The distinction is in the audit log.
     */
    @ExceptionHandler(AuthenticationService.AuthenticationFailedException.class)
    ProblemDetail onAuthenticationFailed(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid credentials");
        problem.setType(URI.create(PROBLEM_BASE + "invalid-credentials"));
        problem.setTitle("Unauthorized");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    /**
     * A role check refused the call — most often AUDITOR attempting a write.
     * Recorded before responding: an attempted privilege violation belongs in the
     * audit trail, not only in a log line that rotates away.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail onAuthorizationDenied(HttpServletRequest request) {
        principal
                .current()
                .ifPresent(user ->
                        audit.accessDenied(user.id(), user.role(), request.getMethod(), request.getRequestURI()));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "your role does not permit this operation");
        problem.setType(URI.create(PROBLEM_BASE + "insufficient-role"));
        problem.setTitle("Forbidden");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidationFailure(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of(
                        "pointer",
                        "/" + field.getField().replace('.', '/'),
                        "message",
                        String.valueOf(field.getDefaultMessage())))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "the request does not satisfy the schema");
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setTitle("Validation failed");
        problem.setProperty("requestId", requestId(request));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail onMissingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        boolean idempotency = "Idempotency-Key".equalsIgnoreCase(e.getHeaderName());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "missing required header %s".formatted(e.getHeaderName()));
        problem.setType(URI.create(PROBLEM_BASE + (idempotency ? "idempotency-key-required" : "missing-header")));
        problem.setTitle(idempotency ? "Idempotency key required" : "Missing header");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    /**
     * Malformed JSON, or a value the type refuses — notably a decimal where
     * {@code amountMinor} expects an integer. That is a 400, and deliberately not
     * a rounding decision.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "the request body could not be read as JSON of the expected shape");
        problem.setType(URI.create(PROBLEM_BASE + "malformed-request"));
        problem.setTitle("Malformed request");
        problem.setProperty("requestId", requestId(request));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception e, HttpServletRequest request) {
        String requestId = requestId(request);
        // Logged with the correlation id, not returned: an internal message can
        // carry schema details a caller has no business seeing.
        log.error("unhandled exception for request {}", requestId, e);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "an unexpected error occurred");
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setTitle("Internal error");
        problem.setProperty("requestId", requestId);
        return problem;
    }

    private static String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return attribute == null ? RequestIdFilter.UNKNOWN : attribute.toString();
    }

    private static String titleOf(LedgerError error) {
        String words = error.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
