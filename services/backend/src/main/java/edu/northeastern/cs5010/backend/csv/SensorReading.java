package edu.northeastern.cs5010.backend.csv;

import java.time.LocalDateTime;

/**
 * One accepted row. {@code null} values are missing readings; they are never filled in.
 *
 * @param timestamp naive local time from the file (the source has no time zone)
 */
public record SensorReading(String sensorId, LocalDateTime timestamp, Double temperatureC, Double humidity) {
}
