package edu.northeastern.cs5010.backend.prediction;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Prediction endpoints of API A. History (list and read) arrives with #39. */
@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionController {
    private final TemperatureForecastService temperature;

    public PredictionController(TemperatureForecastService temperature) {
        this.temperature = temperature;
    }

    @PostMapping("/temperature")
    public ResponseEntity<TemperatureForecast.Response> temperature(@RequestBody TemperatureForecast.Request request) {
        TemperatureForecast.Response response = temperature.forecast(request);
        return ResponseEntity.created(URI.create("/api/v1/predictions/" + response.id())).body(response);
    }
}
