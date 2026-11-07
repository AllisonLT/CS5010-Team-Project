package edu.northeastern.cs5010.backend.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * JSON bodies of API B (docs/api_contracts.md, section 3). Field names are snake_case on the wire.
 * Unknown response fields are ignored, as the versioning rules require.
 */
public final class MlDtos {
    private MlDtos() {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReadyResponse(String status, List<Integer> horizonsMinutes, List<String> sensorIds) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Observation(String timestamp, double temperature, double humidity) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ForecastRequest(String sensorId, double threshold, List<Integer> horizonsMinutes,
                                  List<Observation> history) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HorizonForecast(Integer horizonMinutes, String targetTimestamp, Double predictedTemperature,
                                  Double predictedPeakTemperature, Boolean predictedThresholdCrossing) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForecastResponse(String sensorId, String originTimestamp, Double currentTemperature,
                                   Double threshold, Boolean currentlyAboveThreshold,
                                   List<HorizonForecast> predictions, Double predictedMinTemperature,
                                   Double predictedMaxTemperature, String unit, String modelVersion) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ShelfLifeObservation(String timestamp, Double temperatureC) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShelfLifeRequest(String category, double initialLogCount, double physiologicalStateAlpha0,
                                   Double futureTemperatureC, int intervalMinutes,
                                   List<ShelfLifeObservation> history) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShelfLifeFactors(@JsonProperty("minutes_above_4c") Integer minutesAbove4c, Double peakTemperatureC,
                                   Double meanTemperatureC,
                                   @JsonProperty("growth_share_above_4c") Double growthShareAbove4c) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShelfLifeResponse(String category, String historyStart, String historyEnd,
                                    Double futureTemperatureC, Double remainingShelfLifeHours,
                                    Double shelfLifeUsedFraction, String spoilageRisk, Double estimatedLogCount,
                                    Boolean pastSpoilageLevel, ShelfLifeFactors factors, Integer interpolatedMinutes,
                                    List<String> flags, List<String> limitations, Map<String, Object> model,
                                    String unit) {
    }
}
