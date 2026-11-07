package edu.northeastern.cs5010.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Local configuration under {@code app.*}. Invalid values stop the backend at startup with a message
 * naming the property, instead of failing later on the first request.
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(@Valid @NotNull Ml ml, @Valid @NotNull Storage storage) {

    /**
     * @param baseUrl           FastAPI service, e.g. {@code http://127.0.0.1:8000}
     * @param forecastSensorIds sensors the forecasting artifacts were trained on; used until the ML service
     *                          reports them itself (docs/api_contracts.md, change request 2)
     */
    public record Ml(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout,
                     @NotEmpty List<String> forecastSensorIds) {
    }

    /** @param uploadDirectory root of the backend-managed CSV files (docs/data_model.md) */
    public record Storage(@NotBlank String uploadDirectory, @Positive long maxUploadBytes) {
    }
}
