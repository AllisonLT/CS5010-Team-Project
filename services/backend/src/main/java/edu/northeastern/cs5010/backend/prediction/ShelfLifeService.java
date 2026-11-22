package edu.northeastern.cs5010.backend.prediction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.audit.AuditEventType;
import edu.northeastern.cs5010.backend.audit.AuditRecord;
import edu.northeastern.cs5010.backend.audit.AuditService;
import edu.northeastern.cs5010.backend.csv.MinuteCsv;
import edu.northeastern.cs5010.backend.csv.MinuteReading;
import edu.northeastern.cs5010.backend.dataset.DatasetDocument;
import edu.northeastern.cs5010.backend.dataset.DatasetService;
import edu.northeastern.cs5010.backend.inference.MlClient;
import edu.northeastern.cs5010.backend.inference.MlDtos;
import edu.northeastern.cs5010.backend.inference.MlException;
import edu.northeastern.cs5010.backend.product.ProductDocument;
import edu.northeastern.cs5010.backend.product.ProductRepository;
import edu.northeastern.cs5010.backend.product.ProductStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Estimates the remaining shelf life of a product from the temperature history it is linked to:
 * JavaFX → Spring Boot → FastAPI, then saves and audits the result.
 *
 * <p>The model itself (Tsaloumi et al. 2022, see docs/shelf_life_feasibility.md) runs in the ML service. This
 * class only gathers the product's one-minute readings, checks them against API B before sending, and stores
 * the answer in the same {@code predictions} collection as temperature forecasts, so Prediction History shows
 * both kinds.
 */
@Service
public class ShelfLifeService {
    public static final String SUPPORTED_CATEGORY = "FRESH_POULTRY_AEROBIC";
    static final double DEFAULT_INITIAL_LOG_COUNT = 2.0;
    static final double DEFAULT_ALPHA0 = 1.0;
    static final double MIN_FUTURE_TEMPERATURE_C = 0.0;
    static final double MAX_FUTURE_TEMPERATURE_C = 30.0;
    /** API B accepts 60 to 20,160 one-minute readings (one hour to 14 days). */
    static final int MIN_HISTORY_MINUTES = 60;
    static final int MAX_HISTORY_MINUTES = 20_160;
    /** The ML service interpolates gaps up to this many missing minutes and rejects longer ones. */
    static final int MAX_GAP_MINUTES = 10;

    private final ProductRepository products;
    private final DatasetService datasets;
    private final MlClient ml;
    private final PredictionRepository predictions;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public ShelfLifeService(ProductRepository products, DatasetService datasets, MlClient ml,
                            PredictionRepository predictions, AuditService audit, ObjectMapper json) {
        this(products, datasets, ml, predictions, audit, json, Clock.systemUTC());
    }

    ShelfLifeService(ProductRepository products, DatasetService datasets, MlClient ml,
                     PredictionRepository predictions, AuditService audit, ObjectMapper json, Clock clock) {
        this.products = products;
        this.datasets = datasets;
        this.ml = ml;
        this.predictions = predictions;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public ShelfLife.Response estimate(ShelfLife.Request request) {
        long started = System.nanoTime();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("predictionType", PredictionDocument.PredictionType.SHELF_LIFE.name());
        details.put("productId", request.productId());
        details.put("futureTemperatureC", request.futureTemperatureC());
        AuditRecord.Refs refs = new AuditRecord.Refs(null, request.productId(), null);

        ProductDocument product;
        ProductDocument.TemperatureSource source;
        DatasetDocument dataset;
        List<MlDtos.ShelfLifeObservation> history;
        try {
            validate(request);
            product = activeProduct(request.productId());
            source = linkedSource(product);
            refs = new AuditRecord.Refs(source.datasetId(), product.id(), null);
            details.put("datasetId", source.datasetId());
            details.put("sensorId", source.sensorId());
            dataset = datasets.getReady(source.datasetId());
            LocalDateTime start = parseMinute(source.start(), "start");
            LocalDateTime end = parseMinute(source.end(), "end");
            history = history(datasets.minutes(dataset, source.sensorId(), start, end), start, end);
        } catch (ApiException e) {
            throw failed(refs, details, e);
        }
        String historyStart = history.getFirst().timestamp();
        String historyEnd = history.getLast().timestamp();
        details.put("historyStart", historyStart);
        details.put("historyEnd", historyEnd);

        MlDtos.ShelfLifeResponse result;
        try {
            result = ml.estimateShelfLife(new MlDtos.ShelfLifeRequest(product.category(),
                    orDefault(product.initialLogCount(), DEFAULT_INITIAL_LOG_COUNT),
                    orDefault(product.physiologicalStateAlpha0(), DEFAULT_ALPHA0),
                    request.futureTemperatureC(), 1, history));
        } catch (MlException e) {
            throw failed(refs, details, TemperatureForecastService.toApi(e, "shelf-life estimate"));
        }

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        ShelfLife.SpoilageRisk risk = ShelfLife.SpoilageRisk.valueOf(result.spoilageRisk().toUpperCase(Locale.ROOT));
        ShelfLife.Model model = model(result.model());
        MlDtos.ShelfLifeFactors f = result.factors();
        ShelfLife.Response response = new ShelfLife.Response(id, PredictionDocument.PredictionType.SHELF_LIFE.name(),
                now, product.id(), product.name(), product.category(),
                new ShelfLife.TemperatureSource(dataset.id(), source.sensorId(), historyStart, historyEnd),
                futureTemperature(result, request, history), result.remainingShelfLifeHours(),
                result.shelfLifeUsedFraction(), risk, result.estimatedLogCount(), result.pastSpoilageLevel(),
                new ShelfLife.Factors(f.minutesAbove4c(), f.peakTemperatureC(), f.meanTemperatureC(),
                        f.growthShareAbove4c()),
                result.interpolatedMinutes() == null ? 0 : result.interpolatedMinutes(),
                result.flags() == null ? List.of() : List.copyOf(result.flags()), List.copyOf(result.limitations()),
                model, durationMs);

        boolean warning = isWarning(response);
        Map<String, Object> requestRecord = new LinkedHashMap<>();
        requestRecord.put("productId", product.id());
        requestRecord.put("futureTemperatureC", request.futureTemperatureC());
        requestRecord.put("temperatureSource", Map.of("datasetId", source.datasetId(), "sensorId", source.sensorId(),
                "start", source.start(), "end", source.end()));
        predictions.insert(new PredictionDocument(id, 1, PredictionDocument.PredictionType.SHELF_LIFE, dataset.id(),
                dataset.displayName(), source.sensorId(), product.id(), requestRecord,
                json.convertValue(response, new TypeReference<Map<String, Object>>() { }),
                new PredictionDocument.Summary(warning, headline(response)),
                new PredictionDocument.Model(model.name(), model.version(), model.mlServiceVersion()), durationMs,
                now));

        AuditRecord.Refs saved = new AuditRecord.Refs(dataset.id(), product.id(), id);
        details.put("spoilageRisk", risk.name());
        details.put("remainingShelfLifeHours", response.remainingShelfLifeHours());
        details.put("durationMs", durationMs);
        try {
            audit.record(AuditEventType.PREDICTION_CREATED, ShelfLifeService.class, saved, details);
            if (warning) {
                audit.record(AuditEventType.SPOILAGE_RISK_HIGH, ShelfLifeService.class, saved,
                        Map.of("spoilageRisk", risk.name(), "pastSpoilageLevel", response.pastSpoilageLevel(),
                                "remainingShelfLifeHours", response.remainingShelfLifeHours(),
                                "message", headline(response)));
            }
        } catch (ApiException e) {
            // Without its audit record the result must not exist either (docs/audit_controls.md).
            predictions.deleteById(id);
            throw e;
        }
        return response;
    }

    static void validate(ShelfLife.Request request) {
        List<ApiError.FieldError> errors = new ArrayList<>();
        if (request.productId() == null || request.productId().isBlank()) {
            errors.add(new ApiError.FieldError("productId", null, "Required"));
        }
        Double future = request.futureTemperatureC();
        if (future != null && (!Double.isFinite(future) || future < MIN_FUTURE_TEMPERATURE_C
                || future > MAX_FUTURE_TEMPERATURE_C)) {
            errors.add(new ApiError.FieldError("futureTemperatureC", null, "Must be a number from 0 to 30"));
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation("Check the shelf-life settings: " + errors.getFirst().field() + " "
                    + errors.getFirst().message().toLowerCase(Locale.ROOT) + ".", errors);
        }
    }

    private ProductDocument activeProduct(String id) {
        ProductDocument product = products.findById(id).filter(p -> p.status() == ProductStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("PRODUCT_NOT_FOUND", "No product with id " + id + "."));
        if (!SUPPORTED_CATEGORY.equals(product.category())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_CATEGORY", "Category not supported",
                    "Shelf life can only be estimated for fresh poultry in aerobic storage, not '"
                            + product.category() + "'.");
        }
        return product;
    }

    private static ProductDocument.TemperatureSource linkedSource(ProductDocument product) {
        ProductDocument.TemperatureSource source = product.temperatureSource();
        if (source == null || source.datasetId() == null || source.sensorId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_LINKED", "No temperature history",
                    "Link '" + product.name() + "' to a dataset and time window before estimating its shelf life.");
        }
        return source;
    }

    /**
     * One entry per minute from {@code start} to {@code end}, as API B requires. Minutes missing from the file
     * are sent as {@code null}; missing minutes at either end are dropped, because the first and last entries
     * must have a temperature. Gaps the model would reject are reported here with the time of the gap.
     */
    static List<MlDtos.ShelfLifeObservation> history(List<MinuteReading> minutes, LocalDateTime start,
                                                     LocalDateTime end) {
        if (start.isAfter(end)) {
            throw insufficient("The linked window ends before it starts.");
        }
        Map<LocalDateTime, Double> byMinute = new HashMap<>();
        minutes.forEach(m -> byMinute.put(m.timestamp(), m.temperatureC()));

        LocalDateTime first = start;
        while (!first.isAfter(end) && byMinute.get(first) == null) {
            first = first.plusMinutes(1);
        }
        LocalDateTime last = end;
        while (last.isAfter(first) && byMinute.get(last) == null) {
            last = last.minusMinutes(1);
        }
        if (first.isAfter(end)) {
            throw insufficient("The linked window has no temperature readings.");
        }

        List<MlDtos.ShelfLifeObservation> history = new ArrayList<>();
        int gap = 0;
        for (LocalDateTime t = first; !t.isAfter(last); t = t.plusMinutes(1)) {
            Double temperature = byMinute.get(t);
            gap = temperature == null ? gap + 1 : 0;
            if (gap > MAX_GAP_MINUTES) {
                throw insufficient("The readings stop for more than " + MAX_GAP_MINUTES + " minutes around "
                        + display(t.minusMinutes(gap - 1)) + ". Link a window without long gaps.");
            }
            history.add(new MlDtos.ShelfLifeObservation(MinuteCsv.TIMESTAMP.format(t), temperature));
        }
        if (history.size() < MIN_HISTORY_MINUTES) {
            throw insufficient("The linked window has " + history.size() + " minutes of readings; at least "
                    + MIN_HISTORY_MINUTES + " are needed.");
        }
        if (history.size() > MAX_HISTORY_MINUTES) {
            throw insufficient("The linked window is longer than 14 days (" + history.size()
                    + " minutes). Link a shorter window.");
        }
        return history;
    }

    /** High risk or a product already past the spoilage level is a warning in history and the audit log. */
    static boolean isWarning(ShelfLife.Response response) {
        return response.spoilageRisk() == ShelfLife.SpoilageRisk.HIGH || response.pastSpoilageLevel();
    }

    static String headline(ShelfLife.Response response) {
        if (response.pastSpoilageLevel()) {
            return response.productName() + " has likely reached the spoilage level.";
        }
        String risk = response.spoilageRisk().name().charAt(0)
                + response.spoilageRisk().name().substring(1).toLowerCase(Locale.ROOT);
        return String.format(Locale.ROOT, "%s spoilage risk: about %.1f h of shelf life left at %.1f °C.", risk,
                response.remainingShelfLifeHours(), response.futureTemperatureC());
    }

    private static double futureTemperature(MlDtos.ShelfLifeResponse result, ShelfLife.Request request,
                                            List<MlDtos.ShelfLifeObservation> history) {
        if (result.futureTemperatureC() != null) {
            return result.futureTemperatureC();
        }
        return request.futureTemperatureC() != null ? request.futureTemperatureC()
                : history.getLast().temperatureC();
    }

    private static ShelfLife.Model model(Map<String, Object> model) {
        return new ShelfLife.Model(text(model.get("name")), text(model.get("version")),
                text(model.get("citation_doi")), null);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static LocalDateTime parseMinute(String text, String field) {
        try {
            LocalDateTime t = LocalDateTime.parse(text);
            if (t.getSecond() != 0 || t.getNano() != 0) {
                throw new DateTimeParseException("not a whole minute", text, 0);
            }
            return t;
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCT_NOT_LINKED", "Invalid temperature link",
                    "The product's linked window has an invalid " + field + " time. Link the product again.");
        }
    }

    private static String display(LocalDateTime t) {
        return MinuteCsv.TIMESTAMP.format(t).replace('T', ' ');
    }

    private static ApiException insufficient(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_HISTORY", "Not enough history", detail);
    }

    private ApiException failed(AuditRecord.Refs refs, Map<String, Object> details, ApiException error) {
        String auditId = audit.recordFailure(AuditEventType.PREDICTION_FAILED, ShelfLifeService.class, refs, details,
                error.code(), error.getMessage());
        return error.withAuditId(auditId);
    }
}
