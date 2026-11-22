package edu.northeastern.cs5010.backend.prediction;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/** Prediction and history endpoints of API A. */
@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionController {
    private final TemperatureForecastService temperature;
    private final ShelfLifeService shelfLife;
    private final PredictionHistory history;

    public PredictionController(TemperatureForecastService temperature, ShelfLifeService shelfLife,
                                PredictionHistory history) {
        this.temperature = temperature;
        this.shelfLife = shelfLife;
        this.history = history;
    }

    @PostMapping("/temperature")
    public ResponseEntity<TemperatureForecast.Response> temperature(@RequestBody TemperatureForecast.Request request) {
        TemperatureForecast.Response response = temperature.forecast(request);
        return ResponseEntity.created(URI.create("/api/v1/predictions/" + response.id())).body(response);
    }

    @PostMapping("/shelf-life")
    public ResponseEntity<ShelfLife.Response> shelfLife(@RequestBody ShelfLife.Request request) {
        ShelfLife.Response response = shelfLife.estimate(request);
        return ResponseEntity.created(URI.create("/api/v1/predictions/" + response.id())).body(response);
    }

    @GetMapping
    public PredictionHistory.Page history(
            @RequestParam(required = false) PredictionDocument.PredictionType type,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return history.list(type, datasetId, productId, from, to, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> savedResult(@PathVariable String id) {
        return history.result(id);
    }
}
