package edu.northeastern.cs5010.backend.csv;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of a successful parse.
 *
 * @param recordCount         data rows read (header and repeated header lines excluded)
 * @param acceptedRecordCount rows with a valid temperature
 * @param readings            all rows, sorted by sensor then timestamp; missing values are {@code null}
 * @param warnings            problems that did not stop the upload
 */
public record ParsedCsv(
        CsvFormat format,
        List<String> columns,
        List<String> sensorIds,
        int recordCount,
        int acceptedRecordCount,
        LocalDateTime start,
        LocalDateTime end,
        List<SensorReading> readings,
        List<CsvWarning> warnings) {
}
