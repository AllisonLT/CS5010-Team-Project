package edu.northeastern.cs5010.backend.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * One audit record (AU-3). Written once by {@link AuditService} and never changed.
 *
 * @param source component that produced the event, e.g. {@code backend.DatasetService}
 * @param actor  {@code local-user:<OS user>}; the application has no accounts
 * @param error  required when {@code outcome} is {@code FAILURE}
 */
@Document("audit_logs")
@CompoundIndex(name = "event_time", def = "{'eventType': 1, 'timestamp': -1}")
public record AuditRecord(
        @Id String id,
        int schemaVersion,
        @Indexed Instant timestamp,
        AuditEventType eventType,
        String source,
        String host,
        String actor,
        Outcome outcome,
        Refs refs,
        Map<String, Object> details,
        Failure error) {

    public enum Outcome { SUCCESS, FAILURE }

    public record Refs(String datasetId, String productId, String predictionId) {
        public static final Refs NONE = new Refs(null, null, null);

        public static Refs dataset(String id) {
            return new Refs(id, null, null);
        }
    }

    public record Failure(String code, String message) {
    }
}
