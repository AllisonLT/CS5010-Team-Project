package edu.northeastern.cs5010.backend.audit;

import edu.northeastern.cs5010.backend.api.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {
    private final AuditRepository repository = mock(AuditRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-07T20:01:09.412Z"), ZoneOffset.UTC);
    private final AuditService service = new AuditService(repository, clock);

    @Test
    void successRecordHasEveryRequiredField() {
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));

        String id = service.record(AuditEventType.DATASET_UPLOADED, AuditServiceTest.class,
                AuditRecord.Refs.dataset("d-1"), Map.of("filename", "a.csv"));

        ArgumentCaptor<AuditRecord> saved = ArgumentCaptor.forClass(AuditRecord.class);
        verify(repository).insert(saved.capture());
        AuditRecord record = saved.getValue();
        assertThat(record.id()).isEqualTo(id).isNotBlank();
        assertThat(record.schemaVersion()).isEqualTo(1);
        assertThat(record.timestamp()).isEqualTo(Instant.parse("2026-11-07T20:01:09.412Z"));
        assertThat(record.eventType()).isEqualTo(AuditEventType.DATASET_UPLOADED);
        assertThat(record.source()).isEqualTo("backend.AuditServiceTest");
        assertThat(record.host()).isNotBlank();
        assertThat(record.actor()).startsWith("local-user:");
        assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.SUCCESS);
        assertThat(record.refs().datasetId()).isEqualTo("d-1");
        assertThat(record.details()).containsEntry("filename", "a.csv");
        assertThat(record.error()).isNull();
    }

    @Test
    void failedSuccessWriteIsReportedSoTheCallerCanUndoItsChange() {
        when(repository.insert(any())).thenThrow(new IllegalStateException("mongo down"));

        ApiException e = catchThrowableOfType(ApiException.class, () -> service.record(
                AuditEventType.DATASET_UPLOADED, AuditServiceTest.class, null, Map.of()));

        assertThat(e.code()).isEqualTo("AUDIT_WRITE_FAILED");
        assertThat(e.status().value()).isEqualTo(500);
    }

    @Test
    void failureRecordCarriesTheErrorAndNeverThrows() {
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));

        String id = service.recordFailure(AuditEventType.PREDICTION_FAILED, AuditServiceTest.class, null,
                Map.of(), "ML_UNAVAILABLE", "x".repeat(2000));

        ArgumentCaptor<AuditRecord> saved = ArgumentCaptor.forClass(AuditRecord.class);
        verify(repository).insert(saved.capture());
        assertThat(id).isNotNull();
        assertThat(saved.getValue().outcome()).isEqualTo(AuditRecord.Outcome.FAILURE);
        assertThat(saved.getValue().error().code()).isEqualTo("ML_UNAVAILABLE");
        assertThat(saved.getValue().error().message()).hasSizeLessThan(600).endsWith("(truncated)");

        when(repository.insert(any())).thenThrow(new IllegalStateException("mongo down"));
        assertThat(service.recordFailure(AuditEventType.PREDICTION_FAILED, AuditServiceTest.class, null,
                Map.of(), "ML_UNAVAILABLE", "down")).isNull();
    }
}
