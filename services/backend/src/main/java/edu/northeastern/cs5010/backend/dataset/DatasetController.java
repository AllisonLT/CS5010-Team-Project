package edu.northeastern.cs5010.backend.dataset;

import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Dataset endpoints of API A (docs/api_contracts.md). */
@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {
    static final int DEFAULT_MAX_POINTS = 2000;
    static final int LIMIT_MAX_POINTS = 20000;

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    public record DatasetSummary(String id, String displayName, String originalFilename, DatasetStatus status,
                                 String csvFormat, List<String> sensorIds, int recordCount,
                                 int acceptedRecordCount, DatasetDocument.TimeRange timeRange, Instant createdAt) {
        static DatasetSummary of(DatasetDocument d) {
            return new DatasetSummary(d.id(), d.displayName(), d.originalFilename(), d.status(), d.csvFormat().name(),
                    d.sensorIds(), d.recordCount(), d.acceptedRecordCount(), d.timeRange(), d.createdAt());
        }
    }

    public record DatasetDetail(String id, String displayName, String originalFilename, DatasetStatus status,
                                String csvFormat, List<String> sensorIds, int recordCount, int acceptedRecordCount,
                                DatasetDocument.TimeRange timeRange, Instant createdAt, List<String> columns,
                                DatasetDocument.MinuteSummary minuteSummary,
                                List<DatasetDocument.Warning> warnings) {
        static DatasetDetail of(DatasetDocument d) {
            return new DatasetDetail(d.id(), d.displayName(), d.originalFilename(), d.status(), d.csvFormat().name(),
                    d.sensorIds(), d.recordCount(), d.acceptedRecordCount(), d.timeRange(), d.createdAt(),
                    d.columns(), d.minuteSummary(), d.warnings());
        }
    }

    public record Reading(String timestamp, Double temperatureC, Double humidity) {
    }

    public record ReadingsResponse(String datasetId, String sensorId, int intervalMinutes, boolean downsampled,
                                   List<Reading> readings) {
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatasetDetail> upload(@RequestPart("file") MultipartFile file,
                                                @RequestParam(value = "displayName", required = false) String displayName,
                                                @RequestParam(value = "sensorId", required = false) String sensorId)
            throws IOException {
        DatasetDocument stored = service.upload(new DatasetService.Upload(file.getOriginalFilename(),
                file.getInputStream(), displayName, sensorId));
        return ResponseEntity.created(URI.create("/api/v1/datasets/" + stored.id())).body(DatasetDetail.of(stored));
    }

    @GetMapping
    public List<DatasetSummary> list(@RequestParam(value = "status", required = false) String status) {
        boolean deleted = "DELETED".equalsIgnoreCase(status);
        return service.list(deleted).stream().map(DatasetSummary::of).toList();
    }

    @GetMapping("/{id}")
    public DatasetDetail get(@PathVariable String id) {
        return DatasetDetail.of(service.get(id));
    }

    @GetMapping("/{id}/readings")
    public ReadingsResponse readings(@PathVariable String id, @RequestParam String sensorId,
                                     @RequestParam(required = false) String start,
                                     @RequestParam(required = false) String end,
                                     @RequestParam(defaultValue = "" + DEFAULT_MAX_POINTS) int maxPoints) {
        if (maxPoints < 1 || maxPoints > LIMIT_MAX_POINTS) {
            throw ApiException.validation("maxPoints must be between 1 and " + LIMIT_MAX_POINTS + ".",
                    List.of(new ApiError.FieldError("maxPoints", null, "Out of range")));
        }
        DatasetDocument dataset = service.getReady(id);
        List<MinuteReading> minutes = service.minutes(dataset, sensorId, parseTime("start", start),
                parseTime("end", end));
        int bucket = (int) Math.ceil(minutes.size() / (double) maxPoints);
        List<Reading> readings = bucket <= 1
                ? minutes.stream().map(m -> new Reading(format(m.timestamp()), m.temperatureC(), m.humidity())).toList()
                : downsample(minutes, bucket);
        return new ReadingsResponse(id, sensorId, Math.max(1, bucket), bucket > 1, readings);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    /** Averages each group of {@code bucket} minutes; a group with no readings stays {@code null}. */
    static List<Reading> downsample(List<MinuteReading> minutes, int bucket) {
        List<Reading> result = new ArrayList<>();
        for (int i = 0; i < minutes.size(); i += bucket) {
            List<MinuteReading> group = minutes.subList(i, Math.min(minutes.size(), i + bucket));
            result.add(new Reading(format(group.get(0).timestamp()),
                    mean(group.stream().map(MinuteReading::temperatureC).toList()),
                    mean(group.stream().map(MinuteReading::humidity).toList())));
        }
        return result;
    }

    private static Double mean(List<Double> values) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    private static String format(LocalDateTime time) {
        return edu.northeastern.cs5010.backend.csv.MinuteCsv.TIMESTAMP.format(time);
    }

    static LocalDateTime parseTime(String field, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw ApiException.validation("'" + text + "' is not a local date-time such as 2024-09-09T14:00:00.",
                    List.of(new ApiError.FieldError(field, null, "Expected yyyy-MM-ddTHH:mm:ss")));
        }
    }
}
