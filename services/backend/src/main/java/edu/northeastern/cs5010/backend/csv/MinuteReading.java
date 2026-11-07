package edu.northeastern.cs5010.backend.csv;

import java.time.LocalDateTime;

/**
 * Mean of the readings in {@code (timestamp - 1 min, timestamp]}. {@code null} means the minute had too few
 * readings and is missing.
 *
 * @param readingCount number of valid temperature readings in the minute
 */
public record MinuteReading(String sensorId, LocalDateTime timestamp, Double temperatureC, Double humidity,
                            int readingCount) {
}
