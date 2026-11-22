package edu.northeastern.cs5010.backend.product;

import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.csv.CsvFormat;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import edu.northeastern.cs5010.backend.dataset.DatasetStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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

class ProductServiceTest {
    private static final String DATASET = "6f0c1d1e-8a54-4f3e-9b35-0e2a4f6f1c11";
    private static final Instant NOW = Instant.parse("2026-11-21T20:00:00Z");

    private final ProductRepository repository = mock(ProductRepository.class);
    private final DatasetService datasets = mock(DatasetService.class);
    private final AuditService audit = mock(AuditService.class);
    private final ProductService service = new ProductService(repository, datasets, audit,
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** Readings from 18:04:25 to 20:04:25, so the usable minutes run from 18:05 to 20:05. */
    private final DatasetDocument dataset = new DatasetDocument(DATASET, 1, "Cold room 2", "SENSOR02.csv",
            DatasetStatus.READY, CsvFormat.SENSOR_SEMICOLON, null, List.of("SENSOR02"), List.of(), 0, 0,
            new DatasetDocument.TimeRange("2024-09-08T18:04:25", "2024-09-08T20:04:25"), null, List.of(),
            Instant.EPOCH, Instant.EPOCH);

    private static ProductDocument.TemperatureSource source(String sensor, String start, String end) {
        return new ProductDocument.TemperatureSource(DATASET, sensor, start, end);
    }

    private static Products.Request request(String name, ProductDocument.TemperatureSource source, Long version) {
        return new Products.Request(name, "FRESH_POULTRY_AEROBIC", null, null, source, "  ", version);
    }

    private static ProductDocument stored(long version) {
        return new ProductDocument("p-1", 1, "Batch 14", "FRESH_POULTRY_AEROBIC", 2.0, 1.0, null, null,
                ProductStatus.ACTIVE, version, Instant.EPOCH, Instant.EPOCH);
    }

    @BeforeEach
    void setUp() {
        when(datasets.getReady(DATASET)).thenReturn(dataset);
        when(repository.insert(any(ProductDocument.class))).thenAnswer(call ->
                ((ProductDocument) call.getArgument(0)).withVersion(0L));
        when(repository.save(any(ProductDocument.class))).thenAnswer(call -> {
            ProductDocument p = call.getArgument(0);
            return p.withVersion(p.version() + 1);
        });
        when(audit.recordFailure(any(), any(), any(), anyMap(), anyString(), anyString())).thenReturn("audit-1");
    }

    @Test
    void createAppliesDefaultsTrimsTextAndAuditsWithTheDatasetReference() {
        ProductDocument created = service.create(request("  Chicken breast batch 14 ",
                source("SENSOR02", "2024-09-08T18:05:00", "2024-09-08T20:05:00"), null));

        assertThat(created.id()).isNotBlank();
        assertThat(created.name()).isEqualTo("Chicken breast batch 14");
        assertThat(created.initialLogCount()).isEqualTo(ProductService.DEFAULT_INITIAL_LOG_COUNT);
        assertThat(created.physiologicalStateAlpha0()).isEqualTo(ProductService.DEFAULT_ALPHA0);
        assertThat(created.notes()).isNull();
        assertThat(created.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(created.version()).isZero();
        assertThat(created.createdAt()).isEqualTo(NOW);
        verify(audit).record(eq(AuditEventType.PRODUCT_CREATED), eq(ProductService.class),
                eq(new AuditRecord.Refs(DATASET, created.id(), null)), anyMap());
    }

    @Test
    void productWithoutATemperatureLinkIsAllowed() {
        ProductDocument created = service.create(request("Batch 15", null, null));

        assertThat(created.temperatureSource()).isNull();
        verify(datasets, never()).getReady(any());
    }

    @Test
    void invalidFieldsAreRejectedAndAuditedAsFailures() {
        Products.Request tooLow = new Products.Request("Batch", "FRESH_POULTRY_AEROBIC", 0.5, 1.5, null, null, null);
        ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(tooLow));

        assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(e.toBody().errors()).extracting(error -> error.field())
                .containsExactly("initialLogCount", "physiologicalStateAlpha0");
        assertThat(e.toBody().auditId()).isEqualTo("audit-1");
        verify(audit).recordFailure(eq(AuditEventType.PRODUCT_CREATED), eq(ProductService.class), any(), anyMap(),
                eq("VALIDATION_FAILED"), anyString());

        assertThat(catchThrowableOfType(ApiException.class, () -> service.create(request(" ", null, null)))
                .toBody().errors()).extracting(error -> error.field()).containsExactly("name");
        assertThat(catchThrowableOfType(ApiException.class, () -> service.create(
                new Products.Request("Fish", "FRESH_FISH", null, null, null, null, null))).code())
                .isEqualTo("UNSUPPORTED_CATEGORY");
        verify(repository, never()).insert(any(ProductDocument.class));
    }

    @Test
    void temperatureLinkMustUseAKnownSensorAndWholeMinutesInsideTheDataset() {
        record Case(ProductDocument.TemperatureSource source, String field) {
        }
        List<Case> cases = List.of(
                new Case(source("SENSOR09", "2024-09-08T18:05:00", "2024-09-08T19:00:00"), "temperatureSource.sensorId"),
                new Case(source("SENSOR02", "2024-09-08T18:05:30", "2024-09-08T19:00:00"), "temperatureSource.start"),
                new Case(source("SENSOR02", "18:05", "2024-09-08T19:00:00"), "temperatureSource.start"),
                new Case(source("SENSOR02", "2024-09-08T19:00:00", "2024-09-08T19:00:00"), "temperatureSource.end"),
                new Case(source("SENSOR02", "2024-09-08T18:04:00", "2024-09-08T19:00:00"), "temperatureSource.start"),
                new Case(source("SENSOR02", "2024-09-08T18:05:00", "2024-09-08T20:06:00"), "temperatureSource.end"));
        for (Case c : cases) {
            ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(request("Batch", c.source(),
                    null)));
            assertThat(e.toBody().errors()).as("%s", c.source()).extracting(error -> error.field())
                    .containsExactly(c.field());
        }

        when(datasets.getReady("gone")).thenThrow(new ApiException(HttpStatus.CONFLICT, "DATASET_NOT_READY",
                "Dataset not available", "deleted"));
        ApiException deleted = catchThrowableOfType(ApiException.class, () -> service.create(request("Batch",
                new ProductDocument.TemperatureSource("gone", "SENSOR02", "2024-09-08T18:05:00",
                        "2024-09-08T19:00:00"), null)));
        assertThat(deleted.code()).isEqualTo("DATASET_NOT_READY");
        verify(repository, never()).insert(any(ProductDocument.class));
    }

    @Test
    void updateKeepsCreationTimeRequiresAVersionAndAuditsTheChangedFields() {
        when(repository.findById("p-1")).thenReturn(Optional.of(stored(3)));

        assertThat(catchThrowableOfType(ApiException.class, () -> service.update("p-1",
                request("Batch 14", null, null))).toBody().errors()).extracting(error -> error.field())
                .containsExactly("version");

        ProductDocument updated = service.update("p-1", request("Batch 14 (moved)",
                source("SENSOR02", "2024-09-08T18:05:00", "2024-09-08T19:05:00"), 3L));

        ArgumentCaptor<ProductDocument> saved = ArgumentCaptor.forClass(ProductDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().version()).isEqualTo(3L);
        assertThat(saved.getValue().createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
        assertThat(updated.version()).isEqualTo(4L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> details = ArgumentCaptor.forClass(java.util.Map.class);
        verify(audit).record(eq(AuditEventType.PRODUCT_UPDATED), eq(ProductService.class),
                eq(new AuditRecord.Refs(DATASET, "p-1", null)), details.capture());
        assertThat(details.getValue()).containsEntry("changedFields", List.of("name", "temperatureSource"));
    }

    @Test
    void staleVersionIsAConflict() {
        when(repository.findById("p-1")).thenReturn(Optional.of(stored(4)));
        when(repository.save(any(ProductDocument.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service.update("p-1", request("Batch 14", null, 3L)));

        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        verify(audit).recordFailure(eq(AuditEventType.PRODUCT_UPDATED), any(), any(), anyMap(),
                eq("VERSION_CONFLICT"), anyString());
    }

    @Test
    void deletedProductsAreHiddenAndCannotBeChangedAgain() {
        ProductDocument deleted = new ProductDocument("p-2", 1, "Old", "FRESH_POULTRY_AEROBIC", 2.0, 1.0, null, null,
                ProductStatus.DELETED, 1L, Instant.EPOCH, Instant.EPOCH);
        when(repository.findById("p-1")).thenReturn(Optional.of(stored(1)));
        when(repository.findById("p-2")).thenReturn(Optional.of(deleted));

        service.delete("p-1");

        ArgumentCaptor<ProductDocument> saved = ArgumentCaptor.forClass(ProductDocument.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ProductStatus.DELETED);
        verify(audit).record(eq(AuditEventType.PRODUCT_DELETED), any(), any(), anyMap());
        assertThat(catchThrowableOfType(ApiException.class, () -> service.get("p-2")).code())
                .isEqualTo("PRODUCT_NOT_FOUND");
        assertThat(catchThrowableOfType(ApiException.class, () -> service.delete("p-2")).code())
                .isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void changesAreUndoneWhenTheirAuditRecordCannotBeWritten() {
        doThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_WRITE_FAILED", "Audit failed", "down"))
                .when(audit).record(any(), any(), any(), anyMap());
        when(repository.findById("p-1")).thenReturn(Optional.of(stored(1)));

        ApiException created = catchThrowableOfType(ApiException.class,
                () -> service.create(request("Batch", null, null)));
        ApiException deleted = catchThrowableOfType(ApiException.class, () -> service.delete("p-1"));

        assertThat(created.code()).isEqualTo("AUDIT_WRITE_FAILED");
        assertThat(deleted.code()).isEqualTo("AUDIT_WRITE_FAILED");
        verify(repository).deleteById(any());
        ArgumentCaptor<ProductDocument> saves = ArgumentCaptor.forClass(ProductDocument.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saves.capture());
        assertThat(saves.getAllValues().getLast().status()).isEqualTo(ProductStatus.ACTIVE);
    }
}
