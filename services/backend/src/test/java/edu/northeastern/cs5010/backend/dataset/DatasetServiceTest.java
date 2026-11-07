package edu.northeastern.cs5010.backend.dataset;

import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.config.AppProperties;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Failure paths that need a broken database or audit log, so they use mocks instead of MongoDB. */
class DatasetServiceTest {
    private static final String CSV = "Date;Time;Temperature\n02.09.2024;09:35:00;5\n02.09.2024;09:36:00;6\n";

    @TempDir
    Path uploads;

    private final DatasetRepository repository = mock(DatasetRepository.class);
    private final AuditService audit = mock(AuditService.class);

    private DatasetStorage storage() {
        return new DatasetStorage(new AppProperties(
                new AppProperties.Ml(URI.create("http://127.0.0.1:8000"), Duration.ofSeconds(1), Duration.ofSeconds(4),
                        List.of("SENSOR01")),
                new AppProperties.Storage(uploads.toString(), 1024)));
    }

    private DatasetService service(DatasetStorage storage) {
        return new DatasetService(repository, storage, audit, 1024, Clock.systemUTC());
    }

    private DatasetService.Upload upload(String content) {
        return new DatasetService.Upload("SENSOR01.csv",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), null, null);
    }

    @Test
    void uploadIsUndoneWhenItsAuditRecordCannotBeWritten() throws IOException {
        DatasetStorage storage = storage();
        when(repository.insert(any(DatasetDocument.class))).thenAnswer(call -> call.getArgument(0));
        when(audit.record(eq(AuditEventType.DATASET_UPLOADED), any(), any(), anyMap()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "AUDIT_WRITE_FAILED", "t", "d"));

        ApiException e = catchThrowableOfType(ApiException.class, () -> service(storage).upload(upload(CSV)));

        assertThat(e.code()).isEqualTo("AUDIT_WRITE_FAILED");
        verify(repository).deleteById(any());
        assertFoldersEmpty();
    }

    @Test
    void databaseFailureCleansUpFilesAndIsAudited() throws IOException {
        DatasetStorage storage = storage();
        when(repository.insert(any(DatasetDocument.class))).thenThrow(new IllegalStateException("mongo down"));
        when(audit.recordFailure(eq(AuditEventType.DATASET_UPLOAD_FAILED), any(), any(), anyMap(), any(), any()))
                .thenReturn("audit-1");

        ApiException e = catchThrowableOfType(ApiException.class, () -> service(storage).upload(upload(CSV)));

        assertThat(e.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(e.toBody().auditId()).isEqualTo("audit-1");
        assertFoldersEmpty();
    }

    @Test
    void oversizedUploadIsRejectedWhileReceiving() throws IOException {
        DatasetStorage storage = storage();

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> service(storage).upload(upload(CSV.repeat(100))));

        assertThat(e.code()).isEqualTo("FILE_TOO_LARGE");
        assertThat(e.status().value()).isEqualTo(413);
        assertFoldersEmpty();
    }

    @Test
    void summaryCountsMissingMinutesAndLongestGapPerSensor() {
        LocalDateTime t = LocalDateTime.of(2024, 1, 1, 0, 0);
        DatasetDocument.MinuteSummary summary = DatasetService.summarize(List.of(
                new MinuteReading("A", t, 5.0, null, 1),
                new MinuteReading("A", t.plusMinutes(1), null, null, 0),
                new MinuteReading("A", t.plusMinutes(2), null, null, 0),
                new MinuteReading("B", t, null, null, 0),
                new MinuteReading("B", t.plusMinutes(1), 9.0, null, 1)));

        assertThat(summary).isEqualTo(new DatasetDocument.MinuteSummary(5, 3, 2, 5.0, 9.0));
    }

    private void assertFoldersEmpty() throws IOException {
        try (Stream<Path> datasets = Files.list(uploads.resolve("datasets"));
             Stream<Path> parts = Files.list(uploads.resolve("tmp"))) {
            assertThat(datasets).isEmpty();
            assertThat(parts).isEmpty();
        }
    }
}
