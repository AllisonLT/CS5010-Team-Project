package edu.northeastern.cs5010.backend.audit;

/** AU-2 event catalog (docs/audit_controls.md). */
public enum AuditEventType {
    APPLICATION_STARTED(AuditCategory.SYSTEM),
    APPLICATION_STOPPING(AuditCategory.SYSTEM),
    SYSTEM_ERROR(AuditCategory.SYSTEM),
    DATASET_UPLOADED(AuditCategory.DATASET),
    DATASET_UPLOAD_REJECTED(AuditCategory.DATASET),
    DATASET_UPLOAD_FAILED(AuditCategory.DATASET),
    DATASET_DELETED(AuditCategory.DATASET),
    DATASET_FILE_MISSING(AuditCategory.DATASET),
    PRODUCT_CREATED(AuditCategory.PRODUCT),
    PRODUCT_UPDATED(AuditCategory.PRODUCT),
    PRODUCT_DELETED(AuditCategory.PRODUCT),
    PREDICTION_CREATED(AuditCategory.PREDICTION),
    PREDICTION_FAILED(AuditCategory.PREDICTION),
    THRESHOLD_WARNING_RAISED(AuditCategory.WARNING),
    SPOILAGE_RISK_HIGH(AuditCategory.WARNING),
    AUDIT_MODIFICATION_REJECTED(AuditCategory.ACCESS),
    ML_STARTED(AuditCategory.ML),
    ML_STOPPING(AuditCategory.ML),
    ML_INFERENCE_SUCCEEDED(AuditCategory.ML),
    ML_INFERENCE_FAILED(AuditCategory.ML),
    ML_ALERT_CHECK_SUCCEEDED(AuditCategory.ML),
    ML_ALERT_CHECK_FAILED(AuditCategory.ML),
    ML_THRESHOLD_WARNING(AuditCategory.ML),
    ML_SYSTEM_ERROR(AuditCategory.ML);

    private final AuditCategory category;

    AuditEventType(AuditCategory category) {
        this.category = category;
    }

    public AuditCategory category() {
        return category;
    }
}
