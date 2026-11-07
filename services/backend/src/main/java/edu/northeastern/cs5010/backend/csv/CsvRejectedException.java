package edu.northeastern.cs5010.backend.csv;

import java.util.List;

/** The file cannot be used at all. Nothing from it may be stored. */
public class CsvRejectedException extends RuntimeException {
    private final List<CsvIssue> issues;

    public CsvRejectedException(String message, List<CsvIssue> issues) {
        super(message);
        this.issues = List.copyOf(issues);
    }

    public List<CsvIssue> issues() {
        return issues;
    }
}
