package edu.northeastern.cs5010.backend.api;

import org.springframework.http.HttpStatus;

import java.util.List;

/** A failure with a status and code from the API contract. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<ApiError.FieldError> errors;
    private final String auditId;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of(), null);
    }

    public ApiException(HttpStatus status, String code, String title, String detail,
                        List<ApiError.FieldError> errors, String auditId) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = List.copyOf(errors);
        this.auditId = auditId;
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
    }

    public static ApiException validation(String detail, List<ApiError.FieldError> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Invalid input", detail,
                errors, null);
    }

    public ApiException withAuditId(String id) {
        return new ApiException(status, code, title, getMessage(), errors, id);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public ApiError toBody() {
        return new ApiError(status.value(), code, title, getMessage(), errors, auditId);
    }
}
