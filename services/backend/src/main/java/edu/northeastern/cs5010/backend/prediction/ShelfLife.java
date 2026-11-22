package edu.northeastern.cs5010.backend.prediction;

import java.time.Instant;
import java.util.List;

/** Request and response of {@code POST /api/v1/predictions/shelf-life} (API A, docs/api_contracts.md). */
public final class ShelfLife {
    private ShelfLife() {
    }

    /** @param futureTemperatureC optional, 0–30 °C; default is the last reading in the linked window */
    public record Request(String productId, Double futureTemperatureC) {
    }

    public enum SpoilageRisk { LOW, MEDIUM, HIGH }

    public record TemperatureSource(String datasetId, String sensorId, String start, String end) {
    }

    public record Factors(Integer minutesAbove4C, Double peakTemperatureC, Double meanTemperatureC,
                          Double growthShareAbove4C) {
    }

    public record Model(String name, String version, String citationDoi, String mlServiceVersion) {
    }

    /**
     * @param temperatureSource    the window actually sent to the model; missing minutes at either end are
     *                             trimmed, so it can be shorter than the product's linked window
     * @param interpolatedMinutes  missing minutes inside the window that the model bridged
     */
    public record Response(String id, String type, Instant createdAt, String productId, String productName,
                           String category, TemperatureSource temperatureSource, double futureTemperatureC,
                           double remainingShelfLifeHours, double shelfLifeUsedFraction, SpoilageRisk spoilageRisk,
                           double estimatedLogCount, boolean pastSpoilageLevel, Factors factors,
                           int interpolatedMinutes, List<String> flags, List<String> limitations, Model model,
                           long durationMs) {
    }
}
