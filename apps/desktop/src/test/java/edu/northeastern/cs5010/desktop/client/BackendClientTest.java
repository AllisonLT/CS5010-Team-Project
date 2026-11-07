package edu.northeastern.cs5010.desktop.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class BackendClientTest {
    private HttpServer server;
    private BackendClient client;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new BackendClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            send(exchange, status, body);
        });
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Test
    void readsStatusAndIgnoresUnknownFields() {
        respond("/api/v1/status", 200, """
                {"backend":"UP","database":"DOWN","mlService":"NOT_READY","mlDetail":"models missing",
                 "checkedAt":"2026-11-07T20:00:00Z","somethingNew":1}""");

        ApiModels.Status status = client.status();

        assertThat(status.database()).isEqualTo("DOWN");
        assertThat(status.mlService()).isEqualTo("NOT_READY");
        assertThat(status.checkedAt()).hasToString("2026-11-07T20:00:00Z");
    }

    @Test
    void errorBodyBecomesBackendExceptionWithUserMessage() {
        respond("/api/v1/datasets", 422, """
                {"status":422,"code":"CSV_INVALID","title":"The CSV file could not be used",
                 "detail":"Missing required column 'Temperature'.",
                 "errors":[{"field":"file","row":1,"message":"Missing required column 'Temperature'"}],
                 "auditId":"a-1"}""");

        BackendException e = catchThrowableOfType(BackendException.class, () -> client.datasets());

        assertThat(e.getMessage()).isEqualTo("Missing required column 'Temperature'.");
        assertThat(e.error().code()).isEqualTo("CSV_INVALID");
        assertThat(e.error().errors()).hasSize(1);
        assertThat(e.error().auditId()).isEqualTo("a-1");
    }

    @Test
    void errorWithoutOurFormatStillHasAMessage() {
        respond("/api/v1/datasets", 500, "<html>oops</html>");

        BackendException e = catchThrowableOfType(BackendException.class, () -> client.datasets());

        assertThat(e.error().code()).isEqualTo("HTTP_500");
        assertThat(e.getMessage()).isEqualTo("The backend answered HTTP 500.");
    }

    @Test
    void stoppedBackendIsUnavailable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        BackendUnavailableException e = catchThrowableOfType(BackendUnavailableException.class,
                () -> new BackendClient("http://127.0.0.1:" + port).status());

        assertThat(e.getMessage()).isEqualTo("Cannot connect to the backend at http://127.0.0.1:" + port + ".");
    }

    @Test
    void uploadSendsMultipartWithFileAndOptionalFields(@TempDir Path dir) throws IOException {
        Path csv = dir.resolve("SENSOR02.csv");
        Files.writeString(csv, "Date;Time;Temperature\n02.09.2024;09:35:25;7.5\n");
        respond("/api/v1/datasets", 201, """
                {"id":"d-1","displayName":"Room","status":"READY","sensorIds":["SENSOR02"],"recordCount":1,
                 "acceptedRecordCount":1,"timeRange":{"start":"2024-09-02T09:35:25","end":"2024-09-02T09:35:25"}}""");

        ApiModels.Dataset stored = client.upload(csv, "Room", " ");

        assertThat(stored.id()).isEqualTo("d-1");
        assertThat(lastContentType.get()).startsWith("multipart/form-data; boundary=");
        String body = lastBody.get();
        assertThat(body).contains("name=\"file\"; filename=\"SENSOR02.csv\"")
                .contains("Date;Time;Temperature\n02.09.2024;09:35:25;7.5\n")
                .contains("name=\"displayName\"\r\n\r\nRoom\r\n")
                .doesNotContain("name=\"sensorId\"");
    }

    @Test
    void forecastPostsJsonAndReadsTheResult() {
        respond("/api/v1/predictions/temperature", 201, """
                {"id":"p-1","createdAt":"2026-11-07T20:01:09.412Z","datasetId":"d-1","sensorId":"SENSOR02",
                 "originTimestamp":"2024-09-09T14:00:00","currentTemperatureC":8.1,"thresholdC":10.0,
                 "currentlyAboveThreshold":false,
                 "forecasts":[{"horizonMinutes":15,"targetTimestamp":"2024-09-09T14:15:00",
                   "predictedTemperatureC":8.4,"predictedPeakTemperatureC":9.1,"predictedThresholdCrossing":false}],
                 "predictedMinTemperatureC":8.4,"predictedMaxTemperatureC":8.4,
                 "warning":{"level":"NONE","message":"No threshold crossing is forecast in the selected horizons."},
                 "observedAfterOrigin":[{"timestamp":"2024-09-09T14:15:00","temperatureC":null}],
                 "limitations":["a","b"],"model":{"name":"m","version":"v"},"durationMs":40}""");

        ApiModels.ForecastResult result = client.forecast(new ApiModels.ForecastRequest("d-1", "SENSOR02", null,
                List.of(15), 10.0));

        assertThat(lastContentType.get()).isEqualTo("application/json");
        assertThat(lastBody.get()).contains("\"datasetId\":\"d-1\"").contains("\"horizonsMinutes\":[15]")
                .contains("\"thresholdC\":10.0");
        assertThat(result.forecasts()).hasSize(1);
        assertThat(result.warning().level()).isEqualTo("NONE");
        assertThat(result.observedAfterOrigin().get(0).temperatureC()).isNull();
    }
}
