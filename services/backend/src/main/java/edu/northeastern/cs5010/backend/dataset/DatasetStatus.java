package edu.northeastern.cs5010.backend.dataset;

public enum DatasetStatus {
    /** Selectable. */
    READY,
    /** The stored file disappeared; not selectable. */
    MISSING_FILE,
    /** Soft-deleted by the user. */
    DELETED
}
