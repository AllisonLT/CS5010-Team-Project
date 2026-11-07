package edu.northeastern.cs5010.backend.dataset;

import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.config.AppProperties;
import edu.northeastern.cs5010.backend.csv.CsvParser;
import edu.northeastern.cs5010.backend.csv.CsvRejectedException;
import edu.northeastern.cs5010.backend.csv.MinuteCsv;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import edu.northeastern.cs5010.backend.csv.MinuteResampler;
import edu.northeastern.cs5010.backend.csv.ParsedCsv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Upload, list, read, and delete datasets, following the lifecycle in docs/data_model.md.
 *
 * <p>An upload is received into {@code tmp/}, validated, moved into its own folder, and only then registered in
 * MongoDB. Every failure removes what was written so far, so a rejected or failed upload can never leave a
 * dataset that looks valid. Each outcome is audited (docs/audit_controls.md).
 */
@Service
public class DatasetService {
    public static final int SCHEMA_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    private final DatasetRepository repository;
    private final DatasetStorage storage;
    private final AuditService audit;
    private final long maxUploadBytes;
    private final Clock clock;
    private final CsvParser parser = new CsvParser();
    private final MinuteResampler resampler = new MinuteResampler();

    @Autowired
    public DatasetService(DatasetRepository repository, DatasetStorage storage, AuditService audit,
                          AppProperties properties) {
        this(repository, storage, audit, properties.storage().maxUploadBytes(), Clock.systemUTC());
    }

    DatasetService(DatasetRepository repository, DatasetStorage storage, AuditService audit, long maxUploadBytes,
                   Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.audit = audit;
        this.maxUploadBytes = maxUploadBytes;
        this.clock = clock;
    }

    /** What the user sent. */
    public record Upload(String filename, InputStream content, String displayName, String sensorId) {
    }

    public DatasetDocument upload(Upload upload) {
        String filename = upload.filename() == null ? "" : Path.of(upload.filename()).getFileName().toString();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filename", filename);

        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw rejected(details, new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Unsupported file type", "Only .csv files can be uploaded."));
        }
        String displayName = displayName(upload.displayName(), filename, details);

        DatasetStorage.TempFile received;
        try {
            received = storage.receive(upload.content(), maxUploadBytes);
        } catch (DatasetStorage.FileTooLargeException e) {
            throw rejected(details, new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "File too large", "Files must be at most " + maxUploadBytes / (1024 * 1024) + " MB."));
        } catch (IOException e) {
            throw failed(details, null, e);
        }
        details.put("sizeBytes", received.sizeBytes());
        details.put("sha256", received.sha256());

        ParsedCsv parsed;
        try (Reader reader = Files.newBufferedReader(received.path(), StandardCharsets.UTF_8)) {
            parsed = parser.parse(reader, filename, upload.sensorId());
        } catch (CsvRejectedException e) {
            DatasetStorage.deleteQuietly(received.path());
            List<ApiError.FieldError> errors = e.issues().stream()
                    .map(i -> new ApiError.FieldError(i.field(), i.row(), i.message())).toList();
            throw rejected(details, new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CSV_INVALID",
                    "The CSV file could not be used", e.getMessage(), errors, null));
        } catch (IOException | RuntimeException e) {
            DatasetStorage.deleteQuietly(received.path());
            throw failed(details, null, e);
        }

        String id = UUID.randomUUID().toString();
        DatasetDocument document;
        try {
            Path folder = storage.store(received, id);
            List<MinuteReading> minutes = resampler.resample(parsed.readings());
            Path minuteFile = folder.resolve(DatasetStorage.MINUTE);
            MinuteCsv.write(minuteFile, minutes);
            Instant now = Instant.now(clock);
            document = new DatasetDocument(id, SCHEMA_VERSION, displayName, filename, DatasetStatus.READY,
                    parsed.format(),
                    new DatasetDocument.Storage(storage.relative(folder.resolve(DatasetStorage.SOURCE)),
                            storage.relative(minuteFile), received.sha256(), received.sizeBytes()),
                    parsed.sensorIds(), parsed.columns(), parsed.recordCount(), parsed.acceptedRecordCount(),
                    new DatasetDocument.TimeRange(MinuteCsv.TIMESTAMP.format(parsed.start()),
                            MinuteCsv.TIMESTAMP.format(parsed.end())),
                    summarize(minutes),
                    parsed.warnings().stream()
                            .map(w -> new DatasetDocument.Warning(w.code(), w.message(), w.rowNumbers())).toList(),
                    now, now);
            repository.insert(document);
        } catch (IOException | RuntimeException e) {
            DatasetStorage.deleteQuietly(received.path());
            storage.deleteFolder(id);
            throw failed(details, id, e);
        }

        details.put("recordCount", document.recordCount());
        details.put("sensorIds", document.sensorIds());
        try {
            audit.record(AuditEventType.DATASET_UPLOADED, DatasetService.class, AuditRecord.Refs.dataset(id), details);
        } catch (ApiException e) {
            // A change is never reported as done without its audit record: undo it.
            undoUpload(id);
            throw e;
        }
        return document;
    }

    public List<DatasetDocument> list(boolean deleted) {
        return repository.findByStatusInOrderByCreatedAtDesc(deleted
                ? EnumSet.of(DatasetStatus.DELETED)
                : EnumSet.of(DatasetStatus.READY, DatasetStatus.MISSING_FILE));
    }

    public DatasetDocument get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("DATASET_NOT_FOUND", "No dataset with id " + id + "."));
    }

    /** A dataset that predictions and products may use. */
    public DatasetDocument getReady(String id) {
        DatasetDocument dataset = get(id);
        if (dataset.status() != DatasetStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "DATASET_NOT_READY", "Dataset not available",
                    "The dataset '" + dataset.displayName() + "' is no longer available ("
                            + dataset.status().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ").");
        }
        return dataset;
    }

    /** One-minute readings of one sensor in {@code [start, end]}, oldest first. */
    public List<MinuteReading> minutes(DatasetDocument dataset, String sensorId, LocalDateTime start,
                                       LocalDateTime end) {
        if (!dataset.sensorIds().contains(sensorId)) {
            throw ApiException.validation("The dataset has no sensor '" + sensorId + "'.",
                    List.of(new ApiError.FieldError("sensorId", null, "Unknown sensor for this dataset")));
        }
        try {
            List<MinuteReading> result = new ArrayList<>();
            for (MinuteReading m : MinuteCsv.read(storage.resolve(dataset.storage().processedPath()))) {
                if (m.sensorId().equals(sensorId)
                        && (start == null || !m.timestamp().isBefore(start))
                        && (end == null || !m.timestamp().isAfter(end))) {
                    result.add(m);
                }
            }
            return result;
        } catch (IOException e) {
            markMissing(dataset, e);
            throw new ApiException(HttpStatus.CONFLICT, "DATASET_NOT_READY", "Dataset not available",
                    "The stored file for '" + dataset.displayName() + "' is missing or unreadable.");
        }
    }

    public void delete(String id) {
        DatasetDocument dataset = get(id);
        if (dataset.status() == DatasetStatus.DELETED) {
            return;
        }
        Map<String, Object> details = Map.of("displayName", dataset.displayName());
        try {
            repository.save(dataset.withStatus(DatasetStatus.DELETED, Instant.now(clock)));
        } catch (RuntimeException e) {
            audit.recordFailure(AuditEventType.DATASET_DELETED, DatasetService.class, AuditRecord.Refs.dataset(id),
                    details, "DATABASE_ERROR", e.getMessage());
            throw e;
        }
        try {
            audit.record(AuditEventType.DATASET_DELETED, DatasetService.class, AuditRecord.Refs.dataset(id), details);
        } catch (ApiException e) {
            repository.save(dataset);
            throw e;
        }
        storage.deleteFolder(id);
    }

    /** Startup checks from docs/data_model.md: stale temp files and datasets whose files disappeared. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkStorageOnStartup() {
        int stale = storage.deleteStaleTempFiles(Duration.ofHours(1));
        if (stale > 0) {
            log.info("Deleted {} unfinished upload file(s)", stale);
        }
        try {
            for (DatasetDocument dataset : repository.findByStatus(DatasetStatus.READY)) {
                if (!Files.isRegularFile(storage.resolve(dataset.storage().sourcePath()))
                        || !Files.isRegularFile(storage.resolve(dataset.storage().processedPath()))) {
                    markMissing(dataset, null);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Could not check stored datasets at startup: {}", e.toString());
        }
    }

    static DatasetDocument.MinuteSummary summarize(List<MinuteReading> minutes) {
        int missing = 0;
        int longest = 0;
        int run = 0;
        String sensor = null;
        Double min = null;
        Double max = null;
        for (MinuteReading m : minutes) {
            if (!m.sensorId().equals(sensor)) {
                sensor = m.sensorId();
                run = 0;
            }
            if (m.temperatureC() == null) {
                missing++;
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
                min = min == null ? m.temperatureC() : Math.min(min, m.temperatureC());
                max = max == null ? m.temperatureC() : Math.max(max, m.temperatureC());
            }
        }
        return new DatasetDocument.MinuteSummary(minutes.size(), missing, longest, min, max);
    }

    private void markMissing(DatasetDocument dataset, Exception cause) {
        if (dataset.status() != DatasetStatus.READY) {
            return;
        }
        repository.save(dataset.withStatus(DatasetStatus.MISSING_FILE, Instant.now(clock)));
        audit.recordFailure(AuditEventType.DATASET_FILE_MISSING, DatasetService.class,
                AuditRecord.Refs.dataset(dataset.id()), Map.of("displayName", dataset.displayName()),
                "FILE_MISSING", cause == null ? "Stored file not found at startup" : cause.toString());
    }

    private void undoUpload(String id) {
        try {
            repository.deleteById(id);
        } catch (RuntimeException e) {
            log.error("Could not remove dataset {} after a failed audit write: {}", id, e.toString());
        }
        storage.deleteFolder(id);
    }

    private String displayName(String requested, String filename, Map<String, Object> details) {
        String name = requested == null || requested.isBlank()
                ? filename.substring(0, filename.length() - ".csv".length())
                : requested.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw rejected(details, ApiException.validation("The display name must be 1 to 100 characters.",
                    List.of(new ApiError.FieldError("displayName", null, "Must be 1 to 100 characters"))));
        }
        return name;
    }

    private ApiException rejected(Map<String, Object> details, ApiException error) {
        String auditId = audit.recordFailure(AuditEventType.DATASET_UPLOAD_REJECTED, DatasetService.class, null,
                details, error.code(), error.getMessage());
        return error.withAuditId(auditId);
    }

    private ApiException failed(Map<String, Object> details, String datasetId, Exception cause) {
        log.error("Dataset upload failed", cause);
        String auditId = audit.recordFailure(AuditEventType.DATASET_UPLOAD_FAILED, DatasetService.class,
                datasetId == null ? null : AuditRecord.Refs.dataset(datasetId), details, "STORAGE_ERROR",
                cause.toString());
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Upload failed",
                "The file is valid but could not be saved. Nothing was stored. Check that MongoDB is running "
                        + "and the upload folder is writable, then try again.", List.of(), auditId);
    }
}
