package edu.northeastern.cs5010.backend.csv;

import java.util.List;

/**
 * A problem that did not stop the upload.
 *
 * @param rowNumbers at most {@link CsvParser#MAX_LISTED_ROWS} example line numbers
 */
public record CsvWarning(String code, String message, List<Integer> rowNumbers) {
}
