package edu.northeastern.cs5010.backend.prediction;

import java.time.Instant;
import java.util.List;

/** Request and response of {@code POST /api/v1/predictions/temperature} (API A). */
public final class TemperatureForecast {
    private TemperatureForecast() {
    }

    /** @param originTimestamp optional whole minute; default is the latest minute with enough history */
    public record Request(String datasetId, String sensorId, String originTimestamp, List<Integer> horizonsMinutes,
                          Double thresholdC) {
    }

    public enum WarningLevel { CURRENT_VIOLATION, IMMINENT, WARNING, ADVISORY, NONE }

    public record Forecast(int horizonMinutes, String targetTimestamp, double predictedTemperatureC,
                           double predictedPeakTemperatureC, boolean predictedThresholdCrossing) {
    }

    public record Warning(WarningLevel level, String message) {
    }

    /** @param temperatureC actual reading at the target time from the dataset, or {@code null} */
    public record Observed(String timestamp, Double temperatureC) {
    }

    public record Model(String name, String version, String mlServiceVersion) {
    }

    public record Response(String id, String type, Instant createdAt, String datasetId, String sensorId,
                           String originTimestamp, double currentTemperatureC, double thresholdC,
                           boolean currentlyAboveThreshold, List<Forecast> forecasts,
                           double predictedMinTemperatureC, double predictedMaxTemperatureC, Warning warning,
                           List<Observed> observedAfterOrigin, List<String> limitations, Model model,
                           long durationMs) {
    }
}
