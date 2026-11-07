package edu.northeastern.cs5010.backend.csv;

/**
 * One problem found in an uploaded file.
 *
 * @param field column name, or {@code "file"} for problems with the whole file
 * @param row   1-based line number in the file (the header is line 1), or {@code null}
 * @param message text that can be shown to the user
 */
public record CsvIssue(String field, Integer row, String message) {
}
