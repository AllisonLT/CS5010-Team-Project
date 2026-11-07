package edu.northeastern.cs5010.backend.dataset;

import edu.northeastern.cs5010.backend.csv.CsvFormat;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/** A stored upload (docs/data_model.md, collection {@code datasets}). */
@Document("datasets")
@CompoundIndex(name = "status_created", def = "{'status': 1, 'createdAt': -1}")
public record DatasetDocument(
        @Id String id,
        int schemaVersion,
        String displayName,
        String originalFilename,
        DatasetStatus status,
        CsvFormat csvFormat,
        Storage storage,
        List<String> sensorIds,
        List<String> columns,
        int recordCount,
        int acceptedRecordCount,
        TimeRange timeRange,
        MinuteSummary minuteSummary,
        List<Warning> warnings,
        Instant createdAt,
        Instant updatedAt) {

    /** Paths are relative to {@code app.storage.upload-directory}. */
    public record Storage(String sourcePath, String processedPath, @Indexed String sha256, long sizeBytes) {
    }

    /**
     * Naive local timestamps from the file, stored as ISO text ({@code 2024-09-02T09:35:25}). A
     * {@code LocalDateTime} would be converted to a UTC date using this machine's time zone.
     */
    public record TimeRange(String start, String end) {
    }

    /** @param longestGapMinutes longest run of missing minutes for any sensor */
    public record MinuteSummary(int minuteCount, int missingMinuteCount, int longestGapMinutes,
                                Double temperatureMinC, Double temperatureMaxC) {
    }

    public record Warning(String code, String message, List<Integer> rowNumbers) {
    }

    public DatasetDocument withStatus(DatasetStatus newStatus, Instant now) {
        return new DatasetDocument(id, schemaVersion, displayName, originalFilename, newStatus, csvFormat, storage,
                sensorIds, columns, recordCount, acceptedRecordCount, timeRange, minuteSummary, warnings,
                createdAt, now);
    }
}
