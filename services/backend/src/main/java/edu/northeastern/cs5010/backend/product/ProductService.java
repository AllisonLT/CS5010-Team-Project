package edu.northeastern.cs5010.backend.product;

import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates, edits, and soft-deletes food products and their link to a temperature history.
 *
 * <p>Every change follows the audit rule in docs/audit_controls.md: make the change, write the audit record,
 * and undo the change if the record cannot be written. Rejected requests are audited as failures.
 */
@Service
public class ProductService {
    public static final String SUPPORTED_CATEGORY = "FRESH_POULTRY_AEROBIC";
    public static final double DEFAULT_INITIAL_LOG_COUNT = 2.0;
    public static final double DEFAULT_ALPHA0 = 1.0;
    static final int SCHEMA_VERSION = 1;
    static final int MAX_NAME_LENGTH = 100;
    static final int MAX_NOTES_LENGTH = 500;

    private final ProductRepository repository;
    private final DatasetService datasets;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public ProductService(ProductRepository repository, DatasetService datasets, AuditService audit) {
        this(repository, datasets, audit, Clock.systemUTC());
    }

    ProductService(ProductRepository repository, DatasetService datasets, AuditService audit, Clock clock) {
        this.repository = repository;
        this.datasets = datasets;
        this.audit = audit;
        this.clock = clock;
    }

    /** Active products, sorted by name. */
    public List<ProductDocument> list() {
        return repository.findByStatus(ProductStatus.ACTIVE).stream()
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    public ProductDocument get(String id) {
        return repository.findById(id).filter(p -> p.status() == ProductStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No product with id " + id + "."));
    }

    public ProductDocument create(Products.Request request) {
        Map<String, Object> details = describe(request);
        ProductDocument product;
        try {
            Values values = validate(request);
            Instant now = Instant.now(clock);
            product = new ProductDocument(UUID.randomUUID().toString(), SCHEMA_VERSION, values.name(),
                    values.category(), values.initialLogCount(), values.alpha0(), values.source(), values.notes(),
                    ProductStatus.ACTIVE, null, now, now);
        } catch (ApiException e) {
            throw failed(AuditEventType.PRODUCT_CREATED, refs(null, request.temperatureSource()), details, e);
        }

        ProductDocument saved = repository.insert(product);
        try {
            audit.record(AuditEventType.PRODUCT_CREATED, ProductService.class,
                    refs(saved.id(), saved.temperatureSource()), details);
        } catch (ApiException e) {
            repository.deleteById(saved.id());
            throw e;
        }
        return saved;
    }

    public ProductDocument update(String id, Products.Request request) {
        Map<String, Object> details = describe(request);
        ProductDocument previous;
        ProductDocument changed;
        try {
            previous = get(id);
            if (request.version() == null) {
                throw ApiException.validation("Reload the product and try again: its version is missing.",
                        List.of(new ApiError.FieldError("version", null, "Required")));
            }
            Values values = validate(request);
            changed = new ProductDocument(previous.id(), SCHEMA_VERSION, values.name(), values.category(),
                    values.initialLogCount(), values.alpha0(), values.source(), values.notes(), ProductStatus.ACTIVE,
                    request.version(), previous.createdAt(), Instant.now(clock));
        } catch (ApiException e) {
            throw failed(AuditEventType.PRODUCT_UPDATED, refs(id, request.temperatureSource()), details, e);
        }
        details.put("changedFields", changedFields(previous, changed));

        ProductDocument saved;
        try {
            saved = repository.save(changed);
        } catch (OptimisticLockingFailureException e) {
            throw failed(AuditEventType.PRODUCT_UPDATED, refs(id, request.temperatureSource()), details,
                    new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Product changed elsewhere",
                            "'" + previous.name() + "' was changed after you opened it. Reload it and try again."));
        }
        try {
            audit.record(AuditEventType.PRODUCT_UPDATED, ProductService.class, refs(id, saved.temperatureSource()),
                    details);
        } catch (ApiException e) {
            repository.save(previous.withVersion(saved.version()));
            throw e;
        }
        return saved;
    }

    /** Marks the product deleted. Its past shelf-life estimates and audit records still refer to it. */
    public void delete(String id) {
        Map<String, Object> details = new LinkedHashMap<>();
        ProductDocument previous;
        try {
            previous = get(id);
        } catch (ApiException e) {
            throw failed(AuditEventType.PRODUCT_DELETED, new AuditRecord.Refs(null, id, null), details, e);
        }
        details.put("name", previous.name());
        ProductDocument deleted = repository.save(new ProductDocument(previous.id(), previous.schemaVersion(),
                previous.name(), previous.category(), previous.initialLogCount(), previous.physiologicalStateAlpha0(),
                previous.temperatureSource(), previous.notes(), ProductStatus.DELETED, previous.version(),
                previous.createdAt(), Instant.now(clock)));
        try {
            audit.record(AuditEventType.PRODUCT_DELETED, ProductService.class,
                    refs(id, previous.temperatureSource()), details);
        } catch (ApiException e) {
            repository.save(previous.withVersion(deleted.version()));
            throw e;
        }
    }

    record Values(String name, String category, double initialLogCount, double alpha0,
                  ProductDocument.TemperatureSource source, String notes) {
    }

    /** Checks every field and returns the values to store, with defaults applied. */
    Values validate(Products.Request request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            errors.add(new ApiError.FieldError("name", null, "Must be 1 to " + MAX_NAME_LENGTH + " characters"));
        }
        if (request.category() == null || request.category().isBlank()) {
            errors.add(new ApiError.FieldError("category", null, "Required"));
        }
        double n0 = request.initialLogCount() == null ? DEFAULT_INITIAL_LOG_COUNT : request.initialLogCount();
        if (!inRange(n0, 1.0, 4.0)) {
            errors.add(new ApiError.FieldError("initialLogCount", null, "Must be from 1.0 to 4.0 log CFU/cm²"));
        }
        double alpha0 = request.physiologicalStateAlpha0() == null ? DEFAULT_ALPHA0
                : request.physiologicalStateAlpha0();
        if (!inRange(alpha0, 0.1, 1.0)) {
            errors.add(new ApiError.FieldError("physiologicalStateAlpha0", null, "Must be from 0.1 to 1.0"));
        }
        String notes = request.notes() == null || request.notes().isBlank() ? null : request.notes().strip();
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            errors.add(new ApiError.FieldError("notes", null, "Must be at most " + MAX_NOTES_LENGTH + " characters"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Check the product: " + errors.getFirst().field() + " "
                    + errors.getFirst().message().toLowerCase(Locale.ROOT) + ".", errors);
        }
        if (!SUPPORTED_CATEGORY.equals(request.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CATEGORY", "Category not supported",
                    "Only fresh poultry in aerobic storage (" + SUPPORTED_CATEGORY + ") is supported, not '"
                            + request.category() + "'.");
        }
        return new Values(name, request.category(), n0, alpha0, checkSource(request.temperatureSource()), notes);
    }

    /**
     * A link must point to a READY dataset and one of its sensors, with whole-minute bounds inside the minutes the
     * dataset has. Minutes are labeled by their end (09:35:25 belongs to 09:36), so the first usable minute is the
     * dataset start rounded up.
     */
    private ProductDocument.TemperatureSource checkSource(ProductDocument.TemperatureSource source) {
        if (source == null) {
            return null;
        }
        if (source.datasetId() == null || source.datasetId().isBlank()) {
            throw invalidSource("datasetId", "Choose a dataset.");
        }
        DatasetDocument dataset = datasets.getReady(source.datasetId());
        if (source.sensorId() == null || !dataset.sensorIds().contains(source.sensorId())) {
            throw invalidSource("sensorId", "Choose one of the dataset's sensors: "
                    + String.join(", ", dataset.sensorIds()) + ".");
        }
        LocalDateTime start = minute(source.start(), "start");
        LocalDateTime end = minute(source.end(), "end");
        if (!start.isBefore(end)) {
            throw invalidSource("end", "The end of the window must be after its start.");
        }
        LocalDateTime first = roundUp(LocalDateTime.parse(dataset.timeRange().start()));
        LocalDateTime last = roundUp(LocalDateTime.parse(dataset.timeRange().end()));
        if (start.isBefore(first) || end.isAfter(last)) {
            throw invalidSource(start.isBefore(first) ? "start" : "end", "The window must lie within the dataset ("
                    + display(first) + " to " + display(last) + ").");
        }
        return new ProductDocument.TemperatureSource(dataset.id(), source.sensorId(), iso(start), iso(end));
    }

    private static LocalDateTime minute(String text, String field) {
        try {
            LocalDateTime t = LocalDateTime.parse(Objects.requireNonNull(text));
            if (t.getSecond() != 0 || t.getNano() != 0) {
                throw invalidSource(field, "Use a whole minute such as 2024-09-08T18:05:00.");
            }
            return t;
        } catch (DateTimeParseException | NullPointerException e) {
            throw invalidSource(field, "Use a whole minute such as 2024-09-08T18:05:00.");
        }
    }

    private static LocalDateTime roundUp(LocalDateTime t) {
        LocalDateTime floor = t.truncatedTo(ChronoUnit.MINUTES);
        return floor.equals(t) ? t : floor.plusMinutes(1);
    }

    private static String iso(LocalDateTime t) {
        return t.truncatedTo(ChronoUnit.MINUTES).toString() + ":00";
    }

    private static String display(LocalDateTime t) {
        return iso(t).replace('T', ' ').substring(0, 16);
    }

    private static ApiException invalidSource(String field, String message) {
        return ApiException.validation("Check the temperature link: " + message,
                List.of(new ApiError.FieldError("temperatureSource." + field, null, message)));
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static List<String> changedFields(ProductDocument before, ProductDocument after) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.name(), after.name())) {
            changed.add("name");
        }
        if (!Objects.equals(before.category(), after.category())) {
            changed.add("category");
        }
        if (!Objects.equals(before.initialLogCount(), after.initialLogCount())) {
            changed.add("initialLogCount");
        }
        if (!Objects.equals(before.physiologicalStateAlpha0(), after.physiologicalStateAlpha0())) {
            changed.add("physiologicalStateAlpha0");
        }
        if (!Objects.equals(before.temperatureSource(), after.temperatureSource())) {
            changed.add("temperatureSource");
        }
        if (!Objects.equals(before.notes(), after.notes())) {
            changed.add("notes");
        }
        return changed;
    }

    /** What the audit record says about the request; notes are left out because they are free text. */
    private static Map<String, Object> describe(Products.Request request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", request.name());
        details.put("category", request.category());
        details.put("linked", request.temperatureSource() != null);
        if (request.temperatureSource() != null) {
            details.put("sensorId", request.temperatureSource().sensorId());
            details.put("start", request.temperatureSource().start());
            details.put("end", request.temperatureSource().end());
        }
        return details;
    }

    private static AuditRecord.Refs refs(String productId, ProductDocument.TemperatureSource source) {
        return new AuditRecord.Refs(source == null ? null : source.datasetId(), productId, null);
    }

    private ApiException failed(AuditEventType type, AuditRecord.Refs refs, Map<String, Object> details,
                                ApiException error) {
        String auditId = audit.recordFailure(type, ProductService.class, refs, details, error.code(),
                error.getMessage());
        return error.withAuditId(auditId);
    }
}
