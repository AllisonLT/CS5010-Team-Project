package edu.northeastern.cs5010.backend.api;

import java.util.List;

/**
 * Error body for every failed request (docs/api_contracts.md, "Error format").
 *
 * @param detail  sentence shown to the user as-is
 * @param auditId audit record of the failed operation, or {@code null}
 */
public record ApiError(int status, String code, String title, String detail, List<FieldError> errors,
                       String auditId) {

    /** @param row 1-based CSV line number, or {@code null} */
    public record FieldError(String field, Integer row, String message) {
    }
}
