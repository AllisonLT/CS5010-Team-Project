package edu.northeastern.cs5010.backend.csv;

/**
 * Supported upload layouts (docs/data_model.md).
 *
 * <p>{@code SENSOR_SEMICOLON} is the Zenodo cold-room layout: {@code Date;Time;Temperature;Humidity}
 * with dates as {@code dd.mm.yyyy}, one sensor per file. {@code CANONICAL} has an ISO
 * {@code timestamp} column and may name the sensor in a {@code sensor_id} column.
 */
public enum CsvFormat {
    SENSOR_SEMICOLON,
    CANONICAL
}
