package edu.northeastern.cs5010.backend.product;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A food product and the temperature history it is linked to (docs/data_model.md, collection {@code products}).
 *
 * @param initialLogCount          N0 in log CFU/cm²; {@code null} means the category default
 * @param physiologicalStateAlpha0 α0 of the growth model; {@code null} means the category default
 * @param temperatureSource        {@code null} until the user links a temperature history
 * @param version                  optimistic lock, so two edits cannot silently overwrite each other
 */
@Document("products")
@CompoundIndex(name = "status_name", def = "{'status': 1, 'name': 1}")
public record ProductDocument(
        @Id String id,
        int schemaVersion,
        String name,
        String category,
        Double initialLogCount,
        Double physiologicalStateAlpha0,
        TemperatureSource temperatureSource,
        String notes,
        ProductStatus status,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * A sensor's readings in one dataset between {@code start} and {@code end}, both inclusive whole minutes.
     * Timestamps are naive local text, like {@code DatasetDocument.TimeRange}, so they are never shifted by the
     * machine's time zone.
     */
    public record TemperatureSource(@Indexed String datasetId, String sensorId, String start, String end) {
    }

    /** Used by Spring Data to set the version after a save, since records have no setters. */
    public ProductDocument withVersion(Long newVersion) {
        return new ProductDocument(id, schemaVersion, name, category, initialLogCount, physiologicalStateAlpha0,
                temperatureSource, notes, status, newVersion, createdAt, updatedAt);
    }
}
