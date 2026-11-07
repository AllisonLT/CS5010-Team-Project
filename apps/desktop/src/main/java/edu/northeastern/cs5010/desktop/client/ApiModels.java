package edu.northeastern.cs5010.desktop.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** JSON bodies of API A as the desktop reads them (docs/api_contracts.md). Unknown fields are ignored. */
public final class ApiModels {
    private ApiModels() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiError(int status, String code, String title, String detail, List<FieldError> errors,
                           String auditId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldError(String field, Integer row, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String backend, String database, String mlService, String mlDetail, Instant checkedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capabilities(List<Integer> forecastHorizonsMinutes, int forecastMinimumHistoryMinutes,
                               List<String> forecastSensorIds, long maxUploadBytes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeRange(String start, String end) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MinuteSummary(int minuteCount, int missingMinuteCount, int longestGapMinutes,
                                Double temperatureMinC, Double temperatureMaxC) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DatasetWarning(String code, String message, List<Integer> rowNumbers) {
    }

    /** Summary fields are always present; detail fields are {@code null} in list responses. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dataset(String id, String displayName, String originalFilename, String status, String csvFormat,
                          List<String> sensorIds, int recordCount, int acceptedRecordCount, TimeRange timeRange,
                          Instant createdAt, List<String> columns, MinuteSummary minuteSummary,
                          List<DatasetWarning> warnings) {
    }

    public record ForecastRequest(String datasetId, String sensorId, String originTimestamp,
                                  List<Integer> horizonsMinutes, double thresholdC) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(int horizonMinutes, String targetTimestamp, double predictedTemperatureC,
                           double predictedPeakTemperatureC, boolean predictedThresholdCrossing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Warning(String level, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Observed(String timestamp, Double temperatureC) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Model(String name, String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForecastResult(String id, Instant createdAt, String datasetId, String sensorId,
                                 String originTimestamp, double currentTemperatureC, double thresholdC,
                                 boolean currentlyAboveThreshold, List<Forecast> forecasts,
                                 double predictedMinTemperatureC, double predictedMaxTemperatureC, Warning warning,
                                 List<Observed> observedAfterOrigin, List<String> limitations, Model model,
                                 long durationMs) {
    }
}
