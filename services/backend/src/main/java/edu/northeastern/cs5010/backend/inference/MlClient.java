package edu.northeastern.cs5010.backend.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * HTTP client for the FastAPI service (API B in docs/api_contracts.md).
 *
 * <p>Every failure becomes an {@link MlException} with a {@link MlException.Kind}; callers never receive a
 * partial or default prediction. Prediction calls are not retried.
 */
@Component
public class MlClient {
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public MlClient(AppProperties properties, RestClient.Builder builder, ObjectMapper json) {
        this(builder.requestFactory(requestFactory(properties.ml()))
                .baseUrl(properties.ml().baseUrl().toString()).build(), json);
    }

    MlClient(RestClient http, ObjectMapper json) {
        this.http = http;
        this.json = json;
    }

    private static SimpleClientHttpRequestFactory requestFactory(AppProperties.Ml ml) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ml.connectTimeout());
        factory.setReadTimeout(ml.readTimeout());
        return factory;
    }

    /** {@code GET /api/v1/health}. */
    public void health() {
        call(() -> http.get().uri("/api/v1/health").retrieve().toBodilessEntity());
    }

    /** {@code GET /api/v1/ready}; throws {@link MlException.Kind#NOT_READY} when models are missing. */
    public MlDtos.ReadyResponse ready() {
        return call(() -> http.get().uri("/api/v1/ready").retrieve().body(MlDtos.ReadyResponse.class));
    }

    /** {@code POST /api/v1/predictions/temperature}. The response is checked for every field the backend uses. */
    public MlDtos.ForecastResponse forecastTemperature(MlDtos.ForecastRequest request) {
        MlDtos.ForecastResponse response = call(() -> http.post().uri("/api/v1/predictions/temperature")
                .contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(MlDtos.ForecastResponse.class));
        checkForecast(request, response);
        return response;
    }

    /** {@code POST /api/v1/predictions/shelf-life}. */
    public MlDtos.ShelfLifeResponse estimateShelfLife(MlDtos.ShelfLifeRequest request) {
        MlDtos.ShelfLifeResponse response = call(() -> http.post().uri("/api/v1/predictions/shelf-life")
                .contentType(MediaType.APPLICATION_JSON).body(request)
                .retrieve().body(MlDtos.ShelfLifeResponse.class));
        checkShelfLife(response);
        return response;
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpStatusCodeException e) {
            throw fromStatus(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            if (hasCause(e, SocketTimeoutException.class)) {
                throw new MlException(MlException.Kind.TIMEOUT, "The ML service did not answer in time", e);
            }
            throw new MlException(MlException.Kind.UNAVAILABLE, "The ML service is not reachable", e);
        } catch (RestClientException e) {
            throw new MlException(MlException.Kind.BAD_RESPONSE, "The ML service sent an unreadable response", e);
        }
    }

    private MlException fromStatus(HttpStatusCode status, String body) {
        if (status.value() == 422) {
            return new MlException(MlException.Kind.REJECTED, detail(body));
        }
        if (status.value() == 503) {
            return new MlException(MlException.Kind.NOT_READY, detail(body));
        }
        return new MlException(MlException.Kind.BAD_RESPONSE, "The ML service answered HTTP " + status.value());
    }

    /** FastAPI sends {@code {"detail": "text"}} or {@code {"detail": [{"loc": [...], "msg": "..."}]}}. */
    String detail(String body) {
        try {
            JsonNode detail = json.readTree(body).path("detail");
            if (detail.isTextual()) {
                return detail.asText();
            }
            if (detail.isArray()) {
                List<String> messages = new ArrayList<>();
                for (JsonNode item : detail) {
                    String field = item.path("loc").isArray() && !item.path("loc").isEmpty()
                            ? item.path("loc").get(item.path("loc").size() - 1).asText() + ": " : "";
                    messages.add(field + item.path("msg").asText());
                }
                return String.join("; ", messages);
            }
        } catch (Exception ignored) {
            // Fall through to the raw text.
        }
        return body == null || body.isBlank() ? "No details given" : body;
    }

    private static void checkForecast(MlDtos.ForecastRequest request, MlDtos.ForecastResponse r) {
        if (r == null || r.originTimestamp() == null || r.currentTemperature() == null
                || r.currentlyAboveThreshold() == null || r.predictions() == null
                || r.predictedMinTemperature() == null || r.predictedMaxTemperature() == null) {
            throw bad("forecast response is missing required fields");
        }
        List<Integer> horizons = r.predictions().stream().map(MlDtos.HorizonForecast::horizonMinutes).sorted().toList();
        if (!horizons.equals(request.horizonsMinutes().stream().sorted().toList())) {
            throw bad("forecast horizons " + horizons + " do not match the request " + request.horizonsMinutes());
        }
        for (MlDtos.HorizonForecast h : r.predictions()) {
            if (h.targetTimestamp() == null || !finite(h.predictedTemperature())
                    || !finite(h.predictedPeakTemperature()) || h.predictedThresholdCrossing() == null) {
                throw bad("forecast for " + h.horizonMinutes() + " minutes is incomplete");
            }
        }
    }

    private static void checkShelfLife(MlDtos.ShelfLifeResponse r) {
        if (r == null || !finite(r.remainingShelfLifeHours()) || !finite(r.shelfLifeUsedFraction())
                || r.spoilageRisk() == null || !finite(r.estimatedLogCount()) || r.pastSpoilageLevel() == null
                || r.factors() == null || r.limitations() == null || r.model() == null) {
            throw bad("shelf-life response is missing required fields");
        }
        if (!List.of("low", "medium", "high").contains(r.spoilageRisk())) {
            throw bad("unknown spoilage risk '" + r.spoilageRisk() + "'");
        }
    }

    private static boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private static MlException bad(String message) {
        return new MlException(MlException.Kind.BAD_RESPONSE, "The ML service sent an invalid response: " + message);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
