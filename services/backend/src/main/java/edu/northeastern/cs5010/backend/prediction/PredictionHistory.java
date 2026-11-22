package edu.northeastern.cs5010.backend.prediction;

import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import edu.northeastern.cs5010.backend.dataset.DatasetRepository;
import edu.northeastern.cs5010.backend.product.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read saved results without loading sensor files or calling the model. */
@Service
public class PredictionHistory {
    private final MongoTemplate mongo;
    private final PredictionRepository predictions;
    private final DatasetRepository datasets;
    private final ProductRepository products;

    public PredictionHistory(MongoTemplate mongo, PredictionRepository predictions, DatasetRepository datasets,
                             ProductRepository products) {
        this.mongo = mongo;
        this.predictions = predictions;
        this.datasets = datasets;
        this.products = products;
    }

    public record Summary(String id, PredictionDocument.PredictionType type, Instant createdAt, String datasetId,
                          String datasetName, String sensorId, String productId, String productName,
                          String headline, String modelVersion) {
    }

    public record Page(List<Summary> items, int page, int size, long totalItems) {
    }

    public Page list(PredictionDocument.PredictionType type, String datasetId, String productId,
                     Instant from, Instant to, int page, int size) {
        if (page < 0) {
            throw invalid("page", "Page must be zero or greater.");
        }
        if (size < 1 || size > 100) {
            throw invalid("size", "Page size must be between 1 and 100.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from", "The start of the range must precede or equal the end.");
        }

        Query query = new Query();
        if (type != null) {
            query.addCriteria(Criteria.where("type").is(type));
        }
        if (datasetId != null) {
            query.addCriteria(Criteria.where("datasetId").is(datasetId));
        }
        if (productId != null) {
            query.addCriteria(Criteria.where("productId").is(productId));
        }
        if (from != null || to != null) {
            Criteria createdAt = Criteria.where("createdAt");
            if (from != null) {
                createdAt.gte(from);
            }
            if (to != null) {
                createdAt.lte(to);
            }
            query.addCriteria(createdAt);
        }

        long total = mongo.count(query, PredictionDocument.class);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt", "id")).skip((long) page * size).limit(size);
        query.fields().exclude("request", "result");
        List<PredictionDocument> records = mongo.find(query, PredictionDocument.class);

        // Earlier forecasts lack a dataset name snapshot. Resolve their names in one lookup.
        List<String> legacyIds = records.stream().filter(record -> record.datasetName() == null)
                .map(PredictionDocument::datasetId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> legacyNames = new HashMap<>();
        if (!legacyIds.isEmpty()) {
            datasets.findAllById(legacyIds).forEach(dataset -> legacyNames.put(dataset.id(), dataset.displayName()));
        }

        // Shelf-life estimates show the product's current name. Deleted products are only marked DELETED, so
        // their names still resolve.
        List<String> productIds = records.stream().map(PredictionDocument::productId).filter(Objects::nonNull)
                .distinct().toList();
        Map<String, String> productNames = new HashMap<>();
        if (!productIds.isEmpty()) {
            products.findAllById(productIds).forEach(product -> productNames.put(product.id(), product.name()));
        }

        List<Summary> items = records.stream().map(record -> new Summary(record.id(), record.type(), record.createdAt(),
                record.datasetId(), record.datasetName() == null ? legacyNames.get(record.datasetId())
                        : record.datasetName(),
                record.sensorId(), record.productId(), productNames.get(record.productId()),
                record.summary().headline(), record.model().version()))
                .toList();
        return new Page(items, page, size, total);
    }

    public Map<String, Object> result(String id) {
        return predictions.findById(id).orElseThrow(() -> ApiException.notFound("PREDICTION_NOT_FOUND",
                "The saved prediction could not be found.")).result();
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation(message, List.of(new ApiError.FieldError(field, null, message)));
    }
}
