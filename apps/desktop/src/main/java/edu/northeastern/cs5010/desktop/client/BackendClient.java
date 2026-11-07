package edu.northeastern.cs5010.desktop.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The desktop's only way to reach the system: HTTP calls to the Spring Boot backend (API A). The desktop never
 * talks to MongoDB or the ML service directly (docs/architecture.md).
 *
 * <p>Calls block; screens run them on a background thread.
 */
public class BackendClient {
    public static final String DEFAULT_URL = "http://127.0.0.1:8080";

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public BackendClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** {@code BACKEND_URL} environment variable or {@code backend.url} system property, else the default. */
    public static BackendClient fromEnvironment() {
        String url = System.getProperty("backend.url", System.getenv().getOrDefault("BACKEND_URL", DEFAULT_URL));
        return new BackendClient(url);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ApiModels.Status status() {
        return send(get("/api/v1/status"), new TypeReference<>() { });
    }

    public ApiModels.Capabilities capabilities() {
        return send(get("/api/v1/capabilities"), new TypeReference<>() { });
    }

    public List<ApiModels.Dataset> datasets() {
        return send(get("/api/v1/datasets"), new TypeReference<>() { });
    }

    public ApiModels.Dataset dataset(String id) {
        return send(get("/api/v1/datasets/" + id), new TypeReference<>() { });
    }

    public void deleteDataset(String id) {
        send(request("/api/v1/datasets/" + id).DELETE().build(), null);
    }

    /** {@code POST /api/v1/datasets} as {@code multipart/form-data}. Blank optional fields are left out. */
    public ApiModels.Dataset upload(Path file, String displayName, String sensorId) {
        String boundary = "----cold-storage-" + UUID.randomUUID();
        byte[] body;
        try {
            body = multipart(boundary, file, displayName, sensorId);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        HttpRequest request = request("/api/v1/datasets")
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request, new TypeReference<>() { });
    }

    public ApiModels.ForecastResult forecast(ApiModels.ForecastRequest forecast) {
        try {
            HttpRequest request = request("/api/v1/predictions/temperature")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(forecast)))
                    .build();
            return send(request, new TypeReference<>() { });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] multipart(String boundary, Path file, String displayName, String sensorId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String filename = file.getFileName().toString().replace("\"", "");
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n");
        out.write(Files.readAllBytes(file));
        write(out, "\r\n");
        field(out, boundary, "displayName", displayName);
        field(out, boundary, "sensorId", sensorId);
        write(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value.trim() + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
    }

    private <T> T send(HttpRequest request, TypeReference<T> type) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new BackendUnavailableException(baseUrl, e);
        } catch (HttpTimeoutException e) {
            throw new BackendException(new ApiModels.ApiError(504, "BACKEND_TIMEOUT", "No answer",
                    "The backend did not answer in time. Try again.", List.of(), null));
        } catch (IOException e) {
            throw new BackendUnavailableException(baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
        if (response.statusCode() >= 400) {
            throw new BackendException(error(response));
        }
        if (type == null || response.body().isEmpty()) {
            return null;
        }
        try {
            return json.readValue(response.body(), type);
        } catch (IOException e) {
            throw new BackendException(new ApiModels.ApiError(502, "UNREADABLE_RESPONSE", "Unreadable response",
                    "The backend sent a response the application cannot read.", List.of(), null));
        }
    }

    private ApiModels.ApiError error(HttpResponse<String> response) {
        try {
            ApiModels.ApiError error = json.readValue(response.body(), ApiModels.ApiError.class);
            if (error.detail() != null) {
                return error;
            }
        } catch (IOException ignored) {
            // Not our error format; fall through.
        }
        return new ApiModels.ApiError(response.statusCode(), "HTTP_" + response.statusCode(), "Request failed",
                "The backend answered HTTP " + response.statusCode() + ".", List.of(), null);
    }
}
