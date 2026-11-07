package edu.northeastern.cs5010.backend.api;

import edu.northeastern.cs5010.backend.config.AppProperties;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.inference.MlException;
import edu.northeastern.cs5010.backend.prediction.TemperatureForecastService;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** {@code /status} and {@code /capabilities}, used by the desktop's status bar and forms. */
@RestController
@RequestMapping("/api/v1")
public class SystemController {
    private final MongoTemplate mongo;
    private final MlClient ml;
    private final AppProperties properties;

    public SystemController(MongoTemplate mongo, MlClient ml, AppProperties properties) {
        this.mongo = mongo;
        this.ml = ml;
        this.properties = properties;
    }

    public enum Health { UP, DOWN, NOT_READY }

    public record Status(Health backend, Health database, Health mlService, String mlDetail, Instant checkedAt) {
    }

    public record Range(double min, double max, double defaultValue) {
    }

    public record ProductCategory(String code, String label, Range initialLogCount, Range physiologicalStateAlpha0,
                                  Range validatedTemperatureRangeC) {
    }

    public record Capabilities(List<Integer> forecastHorizonsMinutes, int forecastMinimumHistoryMinutes,
                               List<String> forecastSensorIds, List<ProductCategory> productCategories,
                               long maxUploadBytes, int shelfLifeMaxGapMinutes) {
    }

    /** Always 200, so the desktop can show which part is down. */
    @GetMapping("/status")
    public Status status() {
        Health database;
        try {
            mongo.getDb().runCommand(new Document("ping", 1));
            database = Health.UP;
        } catch (RuntimeException e) {
            database = Health.DOWN;
        }
        Health mlService;
        String detail = null;
        try {
            ml.ready();
            mlService = Health.UP;
        } catch (MlException e) {
            mlService = e.kind() == MlException.Kind.NOT_READY ? Health.NOT_READY : Health.DOWN;
            detail = e.getMessage();
        }
        return new Status(Health.UP, database, mlService, detail, Instant.now());
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        List<String> sensors = properties.ml().forecastSensorIds();
        try {
            MlDtos.ReadyResponse ready = ml.ready();
            if (ready.sensorIds() != null && !ready.sensorIds().isEmpty()) {
                sensors = ready.sensorIds();
            }
        } catch (MlException ignored) {
            // Fall back to the configured list.
        }
        return new Capabilities(TemperatureForecastService.HORIZONS.stream().sorted().toList(),
                TemperatureForecastService.HISTORY_MINUTES, sensors,
                List.of(new ProductCategory("FRESH_POULTRY_AEROBIC", "Fresh poultry (aerobic storage)",
                        new Range(1.0, 4.0, 2.0), new Range(0.1, 1.0, 1.0), new Range(0.0, 30.0, 4.0))),
                properties.storage().maxUploadBytes(), 10);
    }
}
