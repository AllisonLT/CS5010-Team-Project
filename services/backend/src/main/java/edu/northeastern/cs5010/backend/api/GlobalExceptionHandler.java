package edu.northeastern.cs5010.backend.api;

import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;

/** Turns every failure into the error body from docs/api_contracts.md. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(e.toBody());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return api(new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File too large",
                "Files must be at most 50 MB."));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> malformed(Exception e) {
        return api(new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                "The request could not be read: " + e.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String auditId = null;
        if (request.getRequestURI().startsWith("/api/v1/audit-logs")) {
            auditId = audit.recordFailure(AuditEventType.AUDIT_MODIFICATION_REJECTED, GlobalExceptionHandler.class,
                    null, Map.of("method", e.getMethod(), "path", request.getRequestURI()),
                    "METHOD_NOT_ALLOWED", "Audit records cannot be changed or deleted");
        }
        return api(new ApiException(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Not allowed",
                e.getMethod() + " is not allowed on " + request.getRequestURI() + ".", List.of(), auditId));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return api(new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found",
                "No endpoint at /" + e.getResourcePath() + "."));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiError> database(DataAccessResourceFailureException e) {
        log.error("MongoDB unavailable", e);
        return api(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "Database unavailable",
                "MongoDB is not reachable. Start it and try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        String auditId = audit.recordFailure(AuditEventType.SYSTEM_ERROR, GlobalExceptionHandler.class, null,
                Map.of("method", request.getMethod(), "path", request.getRequestURI()),
                "INTERNAL_ERROR", e.toString());
        return api(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error",
                "Something went wrong in the backend. Details are in the backend log.", List.of(), auditId));
    }
}
