package edu.northeastern.cs5010.backend.audit;

/** The collection used for each group of audit events. */
public enum AuditCategory {
    SYSTEM("audit_system"),
    DATASET("audit_dataset"),
    PRODUCT("audit_product"),
    PREDICTION("audit_prediction"),
    WARNING("audit_warning"),
    ACCESS("audit_access"),
    ML("audit_ml");

    private final String collection;

    AuditCategory(String collection) {
        this.collection = collection;
    }

    String collection() {
        return collection;
    }
}
