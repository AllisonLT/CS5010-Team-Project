package edu.northeastern.cs5010.backend.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.northeastern.cs5010.backend.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks the backend side of API B against the example files in docs/contracts/examples, plus every failure
 * the backend must handle (#27, #28).
 */
class MlClientContractTest {
    private static final Path EXAMPLES = Path.of("../../docs/contracts/examples");
    private static final String BASE = "http://127.0.0.1:8000";

    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private MlClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MlClient(builder.build(), json);
    }

    private static String example(String name) throws IOException {
        return Files.readString(EXAMPLES.resolve(name));
    }

    private MlDtos.ForecastRequest forecastRequest() throws IOException {
        return new MlDtos.ForecastRequest("SENSOR02", 10.0, List.of(15, 30), List.of(
                new MlDtos.Observation("2024-09-09T13:59:00", 7.4, 71.0),
                new MlDtos.Observation("2024-09-09T14:00:00", 7.5, 71.2)));
    }

    @Test
    void forecastRequestAndResponseMatchTheContractExamples() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(example("ml_forecast_request.json"), true))
                .andRespond(withSuccess(example("ml_forecast_response.json"), MediaType.APPLICATION_JSON));

        MlDtos.ForecastResponse response = client.forecastTemperature(forecastRequest());

        server.verify();
        assertThat(response.originTimestamp()).isEqualTo("2024-09-09T14:00:00");
        assertThat(response.currentTemperature()).isEqualTo(7.5);
        assertThat(response.currentlyAboveThreshold()).isFalse();
        assertThat(response.predictions()).extracting(MlDtos.HorizonForecast::horizonMinutes).containsExactly(15, 30);
        assertThat(response.predictions().get(1).predictedThresholdCrossing()).isTrue();
        assertThat(response.predictions().get(1).predictedPeakTemperature()).isEqualTo(13.5);
        assertThat(response.predictedMinTemperature()).isEqualTo(7.6);
        assertThat(response.modelVersion()).isNull();
    }

    @Test
    void shelfLifeRequestAndResponseMatchTheContractExamples() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/shelf-life"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(example("ml_shelf_life_request.json"), true))
                .andRespond(withSuccess(example("ml_shelf_life_response.json"), MediaType.APPLICATION_JSON));

        MlDtos.ShelfLifeResponse response = client.estimateShelfLife(new MlDtos.ShelfLifeRequest(
                "FRESH_POULTRY_AEROBIC", 2.0, 1.0, 4.0, 1, List.of(
                        new MlDtos.ShelfLifeObservation("2024-09-08T18:05:00", 6.2),
                        new MlDtos.ShelfLifeObservation("2024-09-08T18:06:00", null))));

        server.verify();
        assertThat(response.remainingShelfLifeHours()).isEqualTo(64.3);
        assertThat(response.spoilageRisk()).isEqualTo("medium");
        assertThat(response.factors().minutesAbove4c()).isEqualTo(1730);
        assertThat(response.factors().growthShareAbove4c()).isEqualTo(0.91);
        assertThat(response.model()).containsEntry("citation_doi", "10.1016/j.psj.2022.101985");
        assertThat(response.limitations()).hasSize(2);
    }

    @Test
    void readyResponseWithAndWithoutSensorList() {
        server.expect(requestTo(BASE + "/api/v1/ready"))
                .andRespond(withSuccess("{\"status\":\"ready\",\"horizons_minutes\":[15,30,60]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/ready"))
                .andRespond(withSuccess("{\"status\":\"ready\",\"horizons_minutes\":[15,30,60],"
                        + "\"sensor_ids\":[\"SENSOR01\"]}", MediaType.APPLICATION_JSON));

        assertThat(client.ready().sensorIds()).isNull();
        assertThat(client.ready().sensorIds()).containsExactly("SENSOR01");
    }

    @Test
    void textValidationErrorIsRejectedWithFastApiDetail() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body(example("ml_error_422_text.json")));

        MlException e = catchThrowableOfType(MlException.class, () -> client.forecastTemperature(forecastRequest()));

        assertThat(e.kind()).isEqualTo(MlException.Kind.REJECTED);
        assertThat(e.getMessage()).isEqualTo("Unsupported sensor SENSOR07 for horizon 15");
    }

    @Test
    void listValidationErrorIsJoinedIntoOneMessage() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body(example("ml_error_422_validation.json")));

        MlException e = catchThrowableOfType(MlException.class, () -> client.forecastTemperature(forecastRequest()));

        assertThat(e.kind()).isEqualTo(MlException.Kind.REJECTED);
        assertThat(e.getMessage())
                .isEqualTo("history: Value error, Most recent 61 readings must be consecutive one-minute observations");
    }

    @Test
    void missingModelsAreNotReady() {
        server.expect(requestTo(BASE + "/api/v1/ready"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"Forecast artifacts are not ready\"}"));

        MlException e = catchThrowableOfType(MlException.class, () -> client.ready());

        assertThat(e.kind()).isEqualTo(MlException.Kind.NOT_READY);
        assertThat(e.getMessage()).isEqualTo("Forecast artifacts are not ready");
    }

    @Test
    void serverErrorAndMalformedOrIncompleteResponsesAreBadResponses() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withSuccess("{\"origin_timestamp\":\"2024-09-09T14:00:00\"}", MediaType.APPLICATION_JSON));
        String wrongHorizons = example("ml_forecast_response.json").replace("\"horizon_minutes\": 30", "\"horizon_minutes\": 60");
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withSuccess(wrongHorizons, MediaType.APPLICATION_JSON));
        String nonFinite = example("ml_forecast_response.json").replace("\"predicted_temperature\": 7.6", "\"predicted_temperature\": null");
        server.expect(requestTo(BASE + "/api/v1/predictions/temperature"))
                .andRespond(withSuccess(nonFinite, MediaType.APPLICATION_JSON));

        for (int i = 0; i < 5; i++) {
            MlException e = catchThrowableOfType(MlException.class, () -> client.forecastTemperature(forecastRequest()));
            assertThat(e.kind()).as("response %d", i).isEqualTo(MlException.Kind.BAD_RESPONSE);
        }
    }

    @Test
    void unknownSpoilageRiskIsABadResponse() throws IOException {
        server.expect(requestTo(BASE + "/api/v1/predictions/shelf-life"))
                .andRespond(withSuccess(example("ml_shelf_life_response.json").replace("\"medium\"", "\"severe\""),
                        MediaType.APPLICATION_JSON));

        MlException e = catchThrowableOfType(MlException.class, () -> client.estimateShelfLife(
                new MlDtos.ShelfLifeRequest("FRESH_POULTRY_AEROBIC", 2.0, 1.0, null, 1, List.of())));

        assertThat(e.kind()).isEqualTo(MlException.Kind.BAD_RESPONSE);
    }

    @Test
    void stoppedServiceIsUnavailable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        MlClient real = realClient("http://127.0.0.1:" + port, Duration.ofSeconds(4));

        MlException e = catchThrowableOfType(MlException.class, real::health);

        assertThat(e.kind()).isEqualTo(MlException.Kind.UNAVAILABLE);
    }

    @Test
    void slowServiceTimesOut() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            MlClient real = realClient("http://127.0.0.1:" + slow.getAddress().getPort(), Duration.ofMillis(200));

            MlException e = catchThrowableOfType(MlException.class, real::health);

            assertThat(e.kind()).isEqualTo(MlException.Kind.TIMEOUT);
        } finally {
            slow.stop(0);
        }
    }

    private MlClient realClient(String url, Duration readTimeout) {
        AppProperties properties = new AppProperties(
                new AppProperties.Ml(URI.create(url), Duration.ofSeconds(1), readTimeout, Arrays.asList("SENSOR02")),
                new AppProperties.Storage("unused", 1));
        return new MlClient(properties, RestClient.builder(), json);
    }
}
