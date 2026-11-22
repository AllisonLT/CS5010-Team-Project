package edu.northeastern.cs5010.backend.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.csv.CsvFormat;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import edu.northeastern.cs5010.backend.dataset.DatasetStatus;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.inference.MlException;
import edu.northeastern.cs5010.backend.product.ProductDocument;
import edu.northeastern.cs5010.backend.product.ProductRepository;
import edu.northeastern.cs5010.backend.product.ProductService;
import edu.northeastern.cs5010.backend.product.ProductStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelfLifeServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2024, 9, 8, 18, 5);
    private static final LocalDateTime END = START.plusMinutes(119);
    private static final String DATASET = "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11";
    private static final String PRODUCT = "0b9a7c2e-3e1f-4c55-9d8e-2a7d5b8f9e40";

    private final ProductRepository products = mock(ProductRepository.class);
    private final DatasetService datasets = mock(DatasetService.class);
    private final MlClient ml = mock(MlClient.class);
    private final PredictionRepository predictions = mock(PredictionRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final ShelfLifeService service = new ShelfLifeService(products, datasets, ml, predictions, audit,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-11-21T20:00:00Z"), ZoneOffset.UTC));

    private final DatasetDocument dataset = new DatasetDocument(DATASET, 1, "Cold room 2", "SENSOR02.csv",
            DatasetStatus.READY, CsvFormat.SENSOR_SEMICOLON, null, List.of("SENSOR02"), List.of(), 0, 0, null,
            null, List.of(), Instant.EPOCH, Instant.EPOCH);

    private static ProductDocument product(String category, ProductDocument.TemperatureSource source,
                                           ProductStatus status) {
        return new ProductDocument(PRODUCT, 1, "Chicken breast batch 14", category, 2.5, null, source, null, status,
                0L, Instant.EPOCH, Instant.EPOCH);
    }

    private static ProductDocument linked() {
        return product(ProductService.SUPPORTED_CATEGORY, new ProductDocument.TemperatureSource(DATASET,
                "SENSOR02", "2024-09-08T18:05:00", "2024-09-08T20:04:00"), ProductStatus.ACTIVE);
    }

    /** 120 minutes from START; {@code temperature} gives the reading of minute i, or null when it is missing. */
    private static List<MinuteReading> minutes(IntFunction<Double> temperature) {
        List<MinuteReading> list = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Double t = temperature.apply(i);
            list.add(new MinuteReading("SENSOR02", START.plusMinutes(i), t, 70.0, t == null ? 0 : 12));
        }
        return list;
    }

    private static MlDtos.ShelfLifeResponse result(String risk, boolean past) {
        return new MlDtos.ShelfLifeResponse("FRESH_POULTRY_AEROBIC", "2024-09-08T18:05:00", "2024-09-08T20:04:00",
                4.0, 64.3, 0.72, risk, 5.6, past, new MlDtos.ShelfLifeFactors(1730, 21.9, 9.8, 0.91), 3, List.of(),
                List.of("Modeled estimate for fresh poultry in aerobic storage. Not a food safety determination."),
                Map.of("name", "pseudomonads_square_root", "version", "tsaloumi2022-v1",
                        "citation_doi", "10.1016/j.psj.2022.101985"), "celsius");
    }

    private void readings(List<MinuteReading> minutes) {
        when(datasets.minutes(dataset, "SENSOR02", START, END)).thenReturn(minutes);
    }

    @BeforeEach
    void setUp() {
        when(products.findById(PRODUCT)).thenReturn(Optional.of(linked()));
        when(datasets.getReady(DATASET)).thenReturn(dataset);
        readings(minutes(i -> 4.0 + i * 0.01));
        when(audit.recordFailure(any(), any(), any(), anyMap(), anyString(), anyString())).thenReturn("audit-1");
    }

    @Test
    void sendsEveryMinuteOfTheLinkedWindowAndSavesAndAuditsTheResult() {
        when(ml.estimateShelfLife(any())).thenReturn(result("medium", false));

        ShelfLife.Response response = service.estimate(new ShelfLife.Request(PRODUCT, 4.0));

        ArgumentCaptor<MlDtos.ShelfLifeRequest> sent = ArgumentCaptor.forClass(MlDtos.ShelfLifeRequest.class);
        verify(ml).estimateShelfLife(sent.capture());
        MlDtos.ShelfLifeRequest request = sent.getValue();
        assertThat(request.category()).isEqualTo("FRESH_POULTRY_AEROBIC");
        assertThat(request.initialLogCount()).isEqualTo(2.5);
        assertThat(request.physiologicalStateAlpha0()).isEqualTo(ProductService.DEFAULT_ALPHA0);
        assertThat(request.futureTemperatureC()).isEqualTo(4.0);
        assertThat(request.intervalMinutes()).isEqualTo(1);
        assertThat(request.history()).hasSize(120);
        assertThat(request.history().getFirst().timestamp()).isEqualTo("2024-09-08T18:05:00");
        assertThat(request.history().getLast().timestamp()).isEqualTo("2024-09-08T20:04:00");

        assertThat(response.type()).isEqualTo("SHELF_LIFE");
        assertThat(response.productName()).isEqualTo("Chicken breast batch 14");
        assertThat(response.spoilageRisk()).isEqualTo(ShelfLife.SpoilageRisk.MEDIUM);
        assertThat(response.remainingShelfLifeHours()).isEqualTo(64.3);
        assertThat(response.factors().minutesAbove4C()).isEqualTo(1730);
        assertThat(response.model().citationDoi()).isEqualTo("10.1016/j.psj.2022.101985");
        assertThat(response.temperatureSource().start()).isEqualTo("2024-09-08T18:05:00");

        ArgumentCaptor<PredictionDocument> saved = ArgumentCaptor.forClass(PredictionDocument.class);
        verify(predictions).insert(saved.capture());
        PredictionDocument document = saved.getValue();
        assertThat(document.id()).isEqualTo(response.id());
        assertThat(document.type()).isEqualTo(PredictionDocument.PredictionType.SHELF_LIFE);
        assertThat(document.productId()).isEqualTo(PRODUCT);
        assertThat(document.datasetName()).isEqualTo("Cold room 2");
        assertThat(document.summary().warning()).isFalse();
        assertThat(document.summary().headline()).startsWith("Medium spoilage risk: about 64.3 h");
        assertThat(document.model().version()).isEqualTo("tsaloumi2022-v1");
        assertThat(document.result()).containsEntry("spoilageRisk", "MEDIUM");

        verify(audit).record(eq(AuditEventType.PREDICTION_CREATED), eq(ShelfLifeService.class),
                eq(new AuditRecord.Refs(DATASET, PRODUCT, response.id())), anyMap());
        verify(audit, never()).record(eq(AuditEventType.SPOILAGE_RISK_HIGH), any(), any(), anyMap());
    }

    @Test
    void highRiskOrSpoiledProductIsAWarningInHistoryAndTheAuditLog() {
        when(ml.estimateShelfLife(any())).thenReturn(result("high", false), result("medium", true));

        ShelfLife.Response high = service.estimate(new ShelfLife.Request(PRODUCT, null));
        ShelfLife.Response spoiled = service.estimate(new ShelfLife.Request(PRODUCT, null));

        verify(audit).record(eq(AuditEventType.SPOILAGE_RISK_HIGH), eq(ShelfLifeService.class),
                eq(new AuditRecord.Refs(DATASET, PRODUCT, high.id())), anyMap());
        verify(audit).record(eq(AuditEventType.SPOILAGE_RISK_HIGH), eq(ShelfLifeService.class),
                eq(new AuditRecord.Refs(DATASET, PRODUCT, spoiled.id())), anyMap());
        assertThat(ShelfLifeService.headline(spoiled)).isEqualTo(
                "Chicken breast batch 14 has likely reached the spoilage level.");
    }

    @Test
    void missingMinutesAtTheEdgesAreTrimmedAndShortGapsAreSentAsNull() {
        readings(minutes(i -> i < 3 || i > 116 || (i >= 50 && i < 60) ? null : 5.0));
        when(ml.estimateShelfLife(any())).thenReturn(result("low", false));

        service.estimate(new ShelfLife.Request(PRODUCT, null));

        ArgumentCaptor<MlDtos.ShelfLifeRequest> sent = ArgumentCaptor.forClass(MlDtos.ShelfLifeRequest.class);
        verify(ml).estimateShelfLife(sent.capture());
        List<MlDtos.ShelfLifeObservation> history = sent.getValue().history();
        assertThat(history).hasSize(114);
        assertThat(history.getFirst().timestamp()).isEqualTo("2024-09-08T18:08:00");
        assertThat(history.getLast().timestamp()).isEqualTo("2024-09-08T20:01:00");
        assertThat(history.stream().filter(o -> o.temperatureC() == null)).hasSize(10);
    }

    @Test
    void gapLongerThanTenMinutesIsRejectedBeforeCallingTheModel() {
        readings(minutes(i -> i >= 40 && i <= 50 ? null : 5.0));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));

        assertThat(e.code()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(e.getMessage()).contains("2024-09-08 18:45");
        assertThat(e.toBody().auditId()).isEqualTo("audit-1");
        verify(ml, never()).estimateShelfLife(any());
        verify(audit).recordFailure(eq(AuditEventType.PREDICTION_FAILED), eq(ShelfLifeService.class),
                eq(new AuditRecord.Refs(DATASET, PRODUCT, null)), anyMap(), eq("INSUFFICIENT_HISTORY"),
                anyString());
    }

    @Test
    void windowShorterThanAnHourIsRejected() {
        readings(minutes(i -> i < 70 ? null : 5.0));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));

        assertThat(e.code()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(e.getMessage()).contains("50 minutes");
    }

    @Test
    void productMustExistBeActiveSupportedAndLinked() {
        when(products.findById("missing")).thenReturn(Optional.empty());
        assertThat(catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request("missing", null))).code())
                .isEqualTo("PRODUCT_NOT_FOUND");

        when(products.findById(PRODUCT)).thenReturn(Optional.of(product(ProductService.SUPPORTED_CATEGORY,
                linked().temperatureSource(), ProductStatus.DELETED)));
        assertThat(catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null))).code())
                .isEqualTo("PRODUCT_NOT_FOUND");

        when(products.findById(PRODUCT)).thenReturn(Optional.of(product("FRESH_FISH", linked().temperatureSource(),
                ProductStatus.ACTIVE)));
        assertThat(catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null))).code())
                .isEqualTo("UNSUPPORTED_CATEGORY");

        when(products.findById(PRODUCT)).thenReturn(Optional.of(product(ProductService.SUPPORTED_CATEGORY, null,
                ProductStatus.ACTIVE)));
        ApiException notLinked = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));
        assertThat(notLinked.code()).isEqualTo("PRODUCT_NOT_LINKED");
        assertThat(notLinked.status().value()).isEqualTo(422);

        verify(ml, never()).estimateShelfLife(any());
        verify(predictions, never()).insert(any(PredictionDocument.class));
    }

    @Test
    void requestFieldsAreValidated() {
        ApiException missing = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(" ", null)));
        assertThat(missing.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.toBody().errors()).extracting(error -> error.field()).containsExactly("productId");

        for (double bad : new double[] {-0.1, 30.1, Double.NaN}) {
            ApiException e = catchThrowableOfType(ApiException.class,
                    () -> service.estimate(new ShelfLife.Request(PRODUCT, bad)));
            assertThat(e.code()).as("future temperature %s", bad).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void modelFailuresAreReportedAndNothingIsSaved() {
        when(ml.estimateShelfLife(any()))
                .thenThrow(new MlException(MlException.Kind.UNAVAILABLE, "down"))
                .thenThrow(new MlException(MlException.Kind.REJECTED, "Gap from 18:45 to 19:00 exceeds 10 minutes"));

        ApiException down = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));
        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));

        assertThat(down.code()).isEqualTo("ML_UNAVAILABLE");
        assertThat(down.getMessage()).contains("No shelf-life estimate was made");
        assertThat(rejected.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(rejected.getMessage()).contains("Gap from 18:45");
        verify(predictions, never()).insert(any(PredictionDocument.class));
    }

    @Test
    void resultIsRemovedWhenItsAuditRecordCannotBeWritten() {
        when(ml.estimateShelfLife(any())).thenReturn(result("low", false));
        doThrow(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_WRITE_FAILED",
                "Audit failed", "down")).when(audit).record(any(), any(), any(), anyMap());

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service.estimate(new ShelfLife.Request(PRODUCT, null)));

        assertThat(e.code()).isEqualTo("AUDIT_WRITE_FAILED");
        ArgumentCaptor<PredictionDocument> saved = ArgumentCaptor.forClass(PredictionDocument.class);
        verify(predictions).insert(saved.capture());
        verify(predictions).deleteById(saved.getValue().id());
    }
}
