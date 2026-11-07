package edu.northeastern.cs5010.backend.prediction;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * A successful prediction (docs/data_model.md, collection {@code predictions}). Written once, never edited.
 * Failed predictions are not stored here; they are audited instead.
 *
 * @param request the user's choices, not the reading history
 * @param result  the response returned to the desktop, stored as-is
 */
@Document("predictions")
@CompoundIndexes({
        @CompoundIndex(name = "type_created", def = "{'type': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "dataset_created", def = "{'datasetId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "product_created", def = "{'productId': 1, 'createdAt': -1}")
})
public record PredictionDocument(
        @Id String id,
        int schemaVersion,
        PredictionType type,
        String datasetId,
        String sensorId,
        String productId,
        Map<String, Object> request,
        Map<String, Object> result,
        Summary summary,
        Model model,
        long durationMs,
        @Indexed Instant createdAt) {

    public enum PredictionType { TEMPERATURE_FORECAST, SHELF_LIFE }

    public record Summary(boolean warning, String headline) {
    }

    public record Model(String name, String version, String mlServiceVersion) {
    }
}
