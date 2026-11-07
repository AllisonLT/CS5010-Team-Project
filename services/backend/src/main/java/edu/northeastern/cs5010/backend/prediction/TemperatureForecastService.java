package edu.northeastern.cs5010.backend.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.config.AppProperties;
import edu.northeastern.cs5010.backend.csv.MinuteCsv;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.inference.MlException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a temperature forecast: JavaFX → Spring Boot → FastAPI, then saves and audits the result.
 *
 * <p>The backend picks the 61 one-minute readings ending at the forecast origin from the dataset's
 * {@code minute.csv} and sends only those. Readings after the origin are never sent to the model; they are
 * returned separately so the user can compare the forecast with what really happened.
 */
@Service
public class TemperatureForecastService {
    public static final int HISTORY_MINUTES = 61;
    public static final Set<Integer> HORIZONS = Set.of(15, 30, 60);
    static final String MODEL_NAME = "damped_trend+future_peak_quantile_0.9";
    static final List<String> LIMITATIONS = List.of(
            "Minimum and maximum summarize the requested point forecasts only, not the full period.",
            "The peak estimate is an upper quantile, not a guarantee or a probability.");

    private final DatasetService datasets;
    private final MlClient ml;
    private final PredictionRepository predictions;
    private final AuditService audit;
    private final ObjectMapper json;
    private final List<String> forecastSensorIds;
    private final Clock clock;

    @Autowired
    public TemperatureForecastService(DatasetService datasets, MlClient ml, PredictionRepository predictions,
                                      AuditService audit, ObjectMapper json, AppProperties properties) {
        this(datasets, ml, predictions, audit, json, properties.ml().forecastSensorIds(), Clock.systemUTC());
    }

    TemperatureForecastService(DatasetService datasets, MlClient ml, PredictionRepository predictions,
                               AuditService audit, ObjectMapper json, List<String> forecastSensorIds, Clock clock) {
        this.datasets = datasets;
        this.ml = ml;
        this.predictions = predictions;
        this.audit = audit;
        this.json = json;
        this.forecastSensorIds = List.copyOf(forecastSensorIds);
        this.clock = clock;
    }

    public TemperatureForecast.Response forecast(TemperatureForecast.Request request) {
        long started = System.nanoTime();
        List<Integer> horizons = validate(request);
        DatasetDocument dataset = datasets.getReady(request.datasetId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("predictionType", PredictionDocument.PredictionType.TEMPERATURE_FORECAST.name());
        details.put("sensorId", request.sensorId());
        details.put("horizonsMinutes", horizons);
        details.put("thresholdC", request.thresholdC());
        AuditRecord.Refs refs = AuditRecord.Refs.dataset(dataset.id());

        List<MinuteReading> minutes;
        LocalDateTime origin;
        try {
            if (!forecastSensorIds.contains(request.sensorId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_SENSOR", "Sensor not supported",
                        "The forecasting model was not trained for " + request.sensorId() + ". Supported sensors: "
                                + String.join(", ", forecastSensorIds) + ".");
            }
            minutes = datasets.minutes(dataset, request.sensorId(), null, null);
            origin = chooseOrigin(minutes, parseOrigin(request.originTimestamp()));
        } catch (ApiException e) {
            throw failed(refs, details, e);
        }
        details.put("originTimestamp", MinuteCsv.TIMESTAMP.format(origin));

        MlDtos.ForecastRequest mlRequest = new MlDtos.ForecastRequest(request.sensorId(), request.thresholdC(),
                horizons, history(minutes, origin));
        MlDtos.ForecastResponse result;
        try {
            result = ml.forecastTemperature(mlRequest);
        } catch (MlException e) {
            throw failed(refs, details, toApi(e, "forecast"));
        }

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);
        TemperatureForecast.Warning warning = warning(result, request.thresholdC());
        String modelVersion = result.modelVersion() == null ? "unversioned" : result.modelVersion();
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        TemperatureForecast.Response response = new TemperatureForecast.Response(id,
                PredictionDocument.PredictionType.TEMPERATURE_FORECAST.name(), now, dataset.id(), request.sensorId(),
                result.originTimestamp(), result.currentTemperature(), request.thresholdC(),
                result.currentlyAboveThreshold(),
                result.predictions().stream()
                        .sorted((a, b) -> Integer.compare(a.horizonMinutes(), b.horizonMinutes()))
                        .map(p -> new TemperatureForecast.Forecast(p.horizonMinutes(), p.targetTimestamp(),
                                p.predictedTemperature(), p.predictedPeakTemperature(),
                                p.predictedThresholdCrossing()))
                        .toList(),
                result.predictedMinTemperature(), result.predictedMaxTemperature(), warning,
                observedAfter(minutes, result), LIMITATIONS,
                new TemperatureForecast.Model(MODEL_NAME, modelVersion, null), durationMs);

        Map<String, Object> requestRecord = new LinkedHashMap<>(details);
        requestRecord.remove("predictionType");
        predictions.insert(new PredictionDocument(id, 1, PredictionDocument.PredictionType.TEMPERATURE_FORECAST,
                dataset.id(), request.sensorId(), null, requestRecord,
                json.convertValue(response, new TypeReference<Map<String, Object>>() { }),
                new PredictionDocument.Summary(warning.level() != TemperatureForecast.WarningLevel.NONE,
                        warning.message()),
                new PredictionDocument.Model(MODEL_NAME, modelVersion, null), durationMs, now));

        AuditRecord.Refs saved = new AuditRecord.Refs(dataset.id(), null, id);
        details.put("durationMs", durationMs);
        try {
            audit.record(AuditEventType.PREDICTION_CREATED, TemperatureForecastService.class, saved, details);
            if (warning.level() != TemperatureForecast.WarningLevel.NONE) {
                audit.record(AuditEventType.THRESHOLD_WARNING_RAISED, TemperatureForecastService.class, saved,
                        Map.of("level", warning.level().name(), "thresholdC", request.thresholdC(),
                                "message", warning.message()));
            }
        } catch (ApiException e) {
            predictions.deleteById(id);
            throw e;
        }
        return response;
    }

    /** Checks the request fields; returns the horizons sorted. */
    static List<Integer> validate(TemperatureForecast.Request request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (request.datasetId() == null || request.datasetId().isBlank()) {
            errors.add(new ApiError.FieldError("datasetId", null, "Required"));
        }
        if (request.sensorId() == null || request.sensorId().isBlank()) {
            errors.add(new ApiError.FieldError("sensorId", null, "Required"));
        }
        List<Integer> horizons = request.horizonsMinutes() == null ? List.of() : request.horizonsMinutes();
        if (horizons.isEmpty() || !HORIZONS.containsAll(horizons) || Set.copyOf(horizons).size() != horizons.size()) {
            errors.add(new ApiError.FieldError("horizonsMinutes", null, "Choose one or more of 15, 30, 60"));
        }
        Double threshold = request.thresholdC();
        if (threshold == null || !Double.isFinite(threshold) || threshold < -50 || threshold > 60) {
            errors.add(new ApiError.FieldError("thresholdC", null, "Must be a number from -50 to 60"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Check the forecast settings: " + errors.get(0).field() + " "
                    + errors.get(0).message().toLowerCase() + ".", errors);
        }
        return horizons.stream().sorted().toList();
    }

    /**
     * The requested origin if it has 61 complete minutes ending at it; otherwise the latest such minute when no
     * origin was requested. A minute is complete when it has both temperature and humidity, which the model needs.
     */
    static LocalDateTime chooseOrigin(List<MinuteReading> minutes, LocalDateTime requested) {
        int run = 0;
        LocalDateTime latest = null;
        LocalDateTime previous = null;
        Map<LocalDateTime, Integer> runEndingAt = new HashMap<>();
        for (MinuteReading m : minutes) {
            boolean consecutive = previous != null && m.timestamp().equals(previous.plusMinutes(1));
            run = complete(m) ? (consecutive ? run + 1 : 1) : 0;
            previous = m.timestamp();
            runEndingAt.put(m.timestamp(), run);
            if (run >= HISTORY_MINUTES) {
                latest = m.timestamp();
            }
        }
        if (requested == null) {
            if (latest == null) {
                throw insufficient("This sensor has no stretch of " + HISTORY_MINUTES
                        + " consecutive minutes with temperature and humidity, so it cannot be forecast.");
            }
            return latest;
        }
        if (runEndingAt.getOrDefault(requested, 0) < HISTORY_MINUTES) {
            throw insufficient("There are not " + HISTORY_MINUTES + " consecutive minutes of readings before "
                    + MinuteCsv.TIMESTAMP.format(requested).replace('T', ' ') + ". Choose a later time.");
        }
        return requested;
    }

    static List<MlDtos.Observation> history(List<MinuteReading> minutes, LocalDateTime origin) {
        LocalDateTime first = origin.minusMinutes(HISTORY_MINUTES - 1);
        return minutes.stream()
                .filter(m -> !m.timestamp().isBefore(first) && !m.timestamp().isAfter(origin))
                .map(m -> new MlDtos.Observation(MinuteCsv.TIMESTAMP.format(m.timestamp()), m.temperatureC(),
                        m.humidity()))
                .toList();
    }

    static TemperatureForecast.Warning warning(MlDtos.ForecastResponse result, double threshold) {
        String limit = String.format(java.util.Locale.ROOT, "%.1f °C", threshold);
        if (Boolean.TRUE.equals(result.currentlyAboveThreshold())) {
            return new TemperatureForecast.Warning(TemperatureForecast.WarningLevel.CURRENT_VIOLATION,
                    "The current reading is already at or above " + limit + ".");
        }
        int first = result.predictions().stream()
                .filter(p -> Boolean.TRUE.equals(p.predictedThresholdCrossing()))
                .mapToInt(MlDtos.HorizonForecast::horizonMinutes).min().orElse(0);
        TemperatureForecast.WarningLevel level = switch (first) {
            case 15 -> TemperatureForecast.WarningLevel.IMMINENT;
            case 30 -> TemperatureForecast.WarningLevel.WARNING;
            case 60 -> TemperatureForecast.WarningLevel.ADVISORY;
            default -> TemperatureForecast.WarningLevel.NONE;
        };
        return level == TemperatureForecast.WarningLevel.NONE
                ? new TemperatureForecast.Warning(level, "No threshold crossing is forecast in the selected horizons.")
                : new TemperatureForecast.Warning(level,
                "The temperature may reach " + limit + " within " + first + " minutes.");
    }

    private static List<TemperatureForecast.Observed> observedAfter(List<MinuteReading> minutes,
                                                                   MlDtos.ForecastResponse result) {
        Map<String, Double> byTime = new HashMap<>();
        minutes.forEach(m -> byTime.put(MinuteCsv.TIMESTAMP.format(m.timestamp()), m.temperatureC()));
        return result.predictions().stream()
                .sorted((a, b) -> Integer.compare(a.horizonMinutes(), b.horizonMinutes()))
                .map(p -> new TemperatureForecast.Observed(p.targetTimestamp(), byTime.get(normalize(p.targetTimestamp()))))
                .toList();
    }

    private static String normalize(String timestamp) {
        try {
            return MinuteCsv.TIMESTAMP.format(LocalDateTime.parse(timestamp));
        } catch (DateTimeParseException e) {
            return timestamp;
        }
    }

    private static boolean complete(MinuteReading m) {
        return m.temperatureC() != null && m.humidity() != null;
    }

    private static LocalDateTime parseOrigin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            LocalDateTime origin = LocalDateTime.parse(text);
            if (origin.getSecond() != 0 || origin.getNano() != 0) {
                throw new DateTimeParseException("not a whole minute", text, 0);
            }
            return origin;
        } catch (DateTimeParseException e) {
            throw ApiException.validation("The forecast time must be a whole minute such as 2024-09-09T14:00:00.",
                    List.of(new ApiError.FieldError("originTimestamp", null, "Expected a whole minute")));
        }
    }

    private static ApiException insufficient(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_HISTORY", "Not enough history", detail);
    }

    /** Maps an ML failure to the API A error the desktop shows (docs/api_contracts.md). */
    static ApiException toApi(MlException e, String what) {
        return switch (e.kind()) {
            case UNAVAILABLE -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ML_UNAVAILABLE",
                    "Prediction service unavailable",
                    "The prediction service is not running. No " + what + " was made.");
            case NOT_READY -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ML_UNAVAILABLE",
                    "Prediction service not ready",
                    "The prediction service is running but its models are missing (" + e.getMessage()
                            + "). No " + what + " was made.");
            case TIMEOUT -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ML_TIMEOUT", "Prediction timed out",
                    "The prediction service did not answer in time. No " + what + " was made. Try again.");
            case BAD_RESPONSE -> new ApiException(HttpStatus.BAD_GATEWAY, "ML_BAD_RESPONSE",
                    "Invalid prediction response", e.getMessage() + ". No " + what + " was made.");
            case REJECTED -> {
                String message = e.getMessage();
                String lower = message.toLowerCase();
                String code = lower.contains("unsupported sensor") ? "UNSUPPORTED_SENSOR"
                        : lower.contains("history") || lower.contains("consecutive") ? "INSUFFICIENT_HISTORY"
                        : "VALIDATION_FAILED";
                yield new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, "Prediction input rejected",
                        "The prediction service rejected the input: " + message);
            }
        };
    }

    private ApiException failed(AuditRecord.Refs refs, Map<String, Object> details, ApiException error) {
        String auditId = audit.recordFailure(AuditEventType.PREDICTION_FAILED, TemperatureForecastService.class, refs,
                details, error.code(), error.getMessage());
        return error.withAuditId(auditId);
    }
}
