package io.catalogsentinel.api.web;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps exceptions to RFC 9457 problem details. Every error body carries a stable machine-readable
 * {@code code} and the request's {@code correlationId}; validation errors carry every field violation.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://catalogsentinel.io/problems/";

    @ExceptionHandler(DomainValidationException.class)
    ResponseEntity<ProblemDetail> validation(DomainValidationException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                ex.violations().size() + " field(s) are invalid", request);
        pd.setProperty("errors", ex.violations().stream().map(v -> fieldError(v.field(), v.message())).toList());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> beanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
                fieldErrors.size() + " field(s) are invalid", request);
        pd.setProperty("errors", fieldErrors.stream()
                .map(f -> fieldError(f.getField(), f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Malformed request body", "The request body is not valid JSON for this endpoint", request));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter",
                "Parameter '" + ex.getName() + "' has an invalid value", request);
        pd.setProperty("errors", List.of(fieldError(ex.getName(), "has an invalid value")));
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(CursorCodec.InvalidCursorException.class)
    ResponseEntity<ProblemDetail> cursor(CursorCodec.InvalidCursorException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Invalid cursor",
                "The pagination cursor was not issued by this API", request));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Resource not found", ex.getMessage(), request));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ConflictException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(HttpStatus.CONFLICT, ex.code(), "Conflict",
                ex.getMessage(), request));
    }

    @ExceptionHandler(UnprocessableException.class)
    ResponseEntity<ProblemDetail> unprocessable(UnprocessableException ex, HttpServletRequest request) {
        HttpStatusCode status = HttpStatusCode.valueOf(422);
        return ResponseEntity.status(status).body(problem(status, ex.code(), "Unprocessable request",
                ex.getMessage(), request));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessResourceFailureException ex, HttpServletRequest request) {
        log.error("database unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Service unavailable",
                        "The database is temporarily unavailable; retry later", request));
    }

    /** Fallback: preserve the status of Spring MVC's own exceptions (405, 415, 404...), 500 for everything else. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            return ResponseEntity.status(status).body(problem(status, "HTTP_" + status.value(),
                    errorResponse.getBody().getTitle(), errorResponse.getBody().getDetail(), request));
        }
        log.error("unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Internal error", "An unexpected error occurred", request));
    }

    static ProblemDetail problem(HttpStatusCode status, String code, String title, String detail,
                                 HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase().replace('_', '-')));
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("code", code);
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        return pd;
    }

    private static Map<String, String> fieldError(String field, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("message", message);
        return m;
    }
}
