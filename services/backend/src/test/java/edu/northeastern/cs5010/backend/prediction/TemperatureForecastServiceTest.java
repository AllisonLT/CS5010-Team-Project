package edu.northeastern.cs5010.backend.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.csv.CsvFormat;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import edu.northeastern.cs5010.backend.dataset.DatasetStatus;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.inference.MlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemperatureForecastServiceTest {
    private static final LocalDateTime T0 = LocalDateTime.of(2024, 9, 9, 13, 0);
    private static final String DATASET = "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11";

    private final DatasetService datasets = mock(DatasetService.class);
    private final MlClient ml = mock(MlClient.class);
    private final PredictionRepository predictions = mock(PredictionRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final TemperatureForecastService service = new TemperatureForecastService(datasets, ml, predictions,
            audit, new ObjectMapper().findAndRegisterModules(), List.of("SENSOR02"),
            Clock.fixed(Instant.parse("2026-11-07T20:00:00Z"), ZoneOffset.UTC));

    private final DatasetDocument dataset = new DatasetDocument(DATASET, 1, "Cold room 2", "SENSOR02.csv",
            DatasetStatus.READY, CsvFormat.SENSOR_SEMICOLON, null, List.of("SENSOR02"), List.of(), 0, 0, null,
            null, List.of(), Instant.EPOCH, Instant.EPOCH);

    /** 120 complete minutes from 13:00, plus a missing minute at 13:30 so the first full window ends at 14:31. */
    private static List<MinuteReading> minutes() {
        List<MinuteReading> list = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            boolean gap = i == 30;
            list.add(new MinuteReading("SENSOR02", T0.plusMinutes(i), gap ? null : 7.0 + i * 0.01, gap ? null : 70.0,
                    gap ? 0 : 12));
        }
        return list;
    }

    private static MlDtos.ForecastResponse response(String origin, boolean above, boolean... crossings) {
        List<MlDtos.HorizonForecast> list = new ArrayList<>();
        int[] horizons = {15, 30, 60};
        for (int i = 0; i < crossings.length; i++) {
            LocalDateTime o = LocalDateTime.parse(origin);
            list.add(new MlDtos.HorizonForecast(horizons[i], o.plusMinutes(horizons[i]).toString() + ":00", 8.0,
                    9.0 + i, crossings[i]));
        }
        return new MlDtos.ForecastResponse("SENSOR02", origin, 8.1, 10.0, above, list, 8.0, 8.0, "celsius", null);
    }

    private static TemperatureForecast.Request request(String origin, List<Integer> horizons) {
        return new TemperatureForecast.Request(DATASET, "SENSOR02", origin, horizons, 10.0);
    }

    @BeforeEach
    void setUp() {
        when(datasets.getReady(DATASET)).thenReturn(dataset);
        when(datasets.minutes(eq(dataset), eq("SENSOR02"), isNull(), isNull())).thenReturn(minutes());
        when(audit.recordFailure(any(), any(), any(), anyMap(), anyString(), anyString())).thenReturn("audit-1");
    }

    @Test
    void sendsExactlyTheSixtyOneMinutesEndingAtTheOriginAndSavesTheResult() {
        when(ml.forecastTemperature(any())).thenReturn(response("2024-09-09T14:40:00", false, false, true));

        TemperatureForecast.Response result = service.forecast(request("2024-09-09T14:40:00", List.of(30, 15)));

        ArgumentCaptor<MlDtos.ForecastRequest> sent = ArgumentCaptor.forClass(MlDtos.ForecastRequest.class);
        verify(ml).forecastTemperature(sent.capture());
        assertThat(sent.getValue().history()).hasSize(61);
        assertThat(sent.getValue().history().get(0).timestamp()).isEqualTo("2024-09-09T13:40:00");
        assertThat(sent.getValue().history().get(60).timestamp()).isEqualTo("2024-09-09T14:40:00");
        assertThat(sent.getValue().horizonsMinutes()).containsExactly(15, 30);

        assertThat(result.warning().level()).isEqualTo(TemperatureForecast.WarningLevel.WARNING);
        assertThat(result.warning().message()).isEqualTo("The temperature may reach 10.0 °C within 30 minutes.");
        // 14:55 is in the dataset (minute 115); 15:10 is after its end.
        assertThat(result.observedAfterOrigin().get(0).temperatureC()).isCloseTo(8.15, within(1e-9));
        assertThat(result.observedAfterOrigin().get(1).temperatureC()).isNull();
        assertThat(result.limitations()).hasSize(2);
        verify(predictions).insert(any(PredictionDocument.class));
        verify(audit).record(eq(AuditEventType.PREDICTION_CREATED), any(), any(), anyMap());
        verify(audit).record(eq(AuditEventType.THRESHOLD_WARNING_RAISED), any(), any(), anyMap());
    }

    @Test
    void defaultOriginIsTheLatestMinuteWithAFullWindow() {
        assertThat(TemperatureForecastService.chooseOrigin(minutes(), null)).isEqualTo(T0.plusMinutes(119));
    }

    @Test
    void originWithoutSixtyOneCompleteMinutesIsRejectedAndAudited() {
        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service.forecast(request("2024-09-09T14:00:00", List.of(15))));

        assertThat(e.code()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(e.toBody().auditId()).isEqualTo("audit-1");
        verify(ml, never()).forecastTemperature(any());
        verify(predictions, never()).insert(any(PredictionDocument.class));
        verify(audit).recordFailure(eq(AuditEventType.PREDICTION_FAILED), any(), any(), anyMap(),
                eq("INSUFFICIENT_HISTORY"), anyString());
    }

    @Test
    void firstFullWindowIsAccepted() {
        assertThat(TemperatureForecastService.chooseOrigin(minutes(), T0.plusMinutes(91))).isEqualTo(T0.plusMinutes(91));
        assertThat(catchThrowableOfType(ApiException.class,
                () -> TemperatureForecastService.chooseOrigin(minutes(), T0.plusMinutes(90))).code())
                .isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void unsupportedSensorIsRejectedBeforeCallingTheModel() {
        ApiException e = catchThrowableOfType(ApiException.class, () -> service.forecast(
                new TemperatureForecast.Request(DATASET, "SENSOR07", null, List.of(15), 10.0)));

        assertThat(e.code()).isEqualTo("UNSUPPORTED_SENSOR");
        assertThat(e.getMessage()).contains("Supported sensors: SENSOR02");
        verify(ml, never()).forecastTemperature(any());
    }

    @Test
    void invalidSettingsAreRejected() {
        for (TemperatureForecast.Request bad : List.of(
                new TemperatureForecast.Request(DATASET, "SENSOR02", null, List.of(), 10.0),
                new TemperatureForecast.Request(DATASET, "SENSOR02", null, List.of(20), 10.0),
                new TemperatureForecast.Request(DATASET, "SENSOR02", null, List.of(15, 15), 10.0),
                new TemperatureForecast.Request(DATASET, "SENSOR02", null, List.of(15), 99.0),
                new TemperatureForecast.Request(DATASET, "SENSOR02", null, List.of(15), null),
                new TemperatureForecast.Request(DATASET, "SENSOR02", "2024-09-09T14:00:30", List.of(15), 10.0))) {
            assertThat(catchThrowableOfType(ApiException.class, () -> service.forecast(bad)).code())
                    .isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void everyMlFailureBecomesAnErrorWithoutAPrediction() {
        record Case(MlException.Kind kind, String message, String code, int status) {
        }
        List<Case> cases = List.of(
                new Case(MlException.Kind.UNAVAILABLE, "down", "ML_UNAVAILABLE", 503),
                new Case(MlException.Kind.NOT_READY, "Forecast artifacts are not ready", "ML_UNAVAILABLE", 503),
                new Case(MlException.Kind.TIMEOUT, "slow", "ML_TIMEOUT", 504),
                new Case(MlException.Kind.BAD_RESPONSE, "garbled", "ML_BAD_RESPONSE", 502),
                new Case(MlException.Kind.REJECTED, "Unsupported sensor SENSOR02 for horizon 15", "UNSUPPORTED_SENSOR", 422),
                new Case(MlException.Kind.REJECTED, "Insufficient valid consecutive history", "INSUFFICIENT_HISTORY", 422),
                new Case(MlException.Kind.REJECTED, "Threshold must be finite", "VALIDATION_FAILED", 422));
        for (Case c : cases) {
            doThrow(new MlException(c.kind(), c.message())).when(ml).forecastTemperature(any());

            ApiException e = catchThrowableOfType(ApiException.class, () -> service.forecast(request(null, List.of(15))));

            assertThat(e.code()).as(c.toString()).isEqualTo(c.code());
            assertThat(e.status().value()).as(c.toString()).isEqualTo(c.status());
            assertThat(e.toBody().auditId()).isEqualTo("audit-1");
        }
        verify(predictions, never()).insert(any(PredictionDocument.class));
    }

    @Test
    void predictionIsRemovedWhenItsAuditRecordCannotBeWritten() {
        when(ml.forecastTemperature(any())).thenReturn(response("2024-09-09T14:59:00", false, false));
        when(audit.record(eq(AuditEventType.PREDICTION_CREATED), any(), any(), anyMap()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "AUDIT_WRITE_FAILED", "t", "d"));

        ApiException e = catchThrowableOfType(ApiException.class, () -> service.forecast(request(null, List.of(15))));

        assertThat(e.code()).isEqualTo("AUDIT_WRITE_FAILED");
        verify(predictions).deleteById(anyString());
    }

    @Test
    void warningLevelsFollowTheEarliestCrossing() {
        assertThat(TemperatureForecastService.warning(response("2024-09-09T14:00:00", true, false), 10).level())
                .isEqualTo(TemperatureForecast.WarningLevel.CURRENT_VIOLATION);
        assertThat(TemperatureForecastService.warning(response("2024-09-09T14:00:00", false, true, true, true), 10).level())
                .isEqualTo(TemperatureForecast.WarningLevel.IMMINENT);
        assertThat(TemperatureForecastService.warning(response("2024-09-09T14:00:00", false, false, false, true), 10).level())
                .isEqualTo(TemperatureForecast.WarningLevel.ADVISORY);
        assertThat(TemperatureForecastService.warning(response("2024-09-09T14:00:00", false, false, false, false), 10).level())
                .isEqualTo(TemperatureForecast.WarningLevel.NONE);
    }
}
