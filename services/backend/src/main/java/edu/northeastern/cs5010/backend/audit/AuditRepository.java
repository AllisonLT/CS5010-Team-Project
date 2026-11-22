package edu.northeastern.cs5010.backend.audit;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.UnionWithOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Insert and read only (AU-9). This deliberately does not extend Spring Data's {@code CrudRepository},
 * which would add update and delete operations for audit records.
 */
@Repository
public class AuditRepository {
    private final MongoTemplate mongo;

    public AuditRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public AuditRecord insert(AuditRecord record) {
        return mongo.insert(record, record.category().collection());
    }

    public List<AuditRecord> findNewestFirst(Criteria criteria, int page, int size) {
        return page(criteria, EnumSet.allOf(AuditCategory.class), page, size).items();
    }

    public long count(Criteria criteria) {
        return page(criteria, EnumSet.allOf(AuditCategory.class), 0, 1).total();
    }

    Page page(Criteria criteria, Set<AuditCategory> categories, int page, int size) {
        var match = Aggregation.match(criteria);
        List<AggregationOperation> stages = new ArrayList<>();
        stages.add(match);
        for (AuditCategory category : categories) {
            stages.add(UnionWithOperation.unionWith(category.collection()).pipeline(match));
        }
        stages.add(Aggregation.facet(
                        Aggregation.sort(Sort.Direction.DESC, "timestamp", "_id"),
                        Aggregation.skip((long) page * size),
                        Aggregation.limit(size)).as("items")
                .and(Aggregation.count().as("total")).as("counts"));
        // Include earlier audit records without moving or rewriting them.
        PageResult result = mongo.aggregate(Aggregation.newAggregation(AuditRecord.class, stages),
                "audit_logs", PageResult.class).getUniqueMappedResult();
        if (result == null) {
            return new Page(List.of(), page, size, 0);
        }
        long total = result.counts().isEmpty() ? 0 : result.counts().getFirst().total();
        return new Page(result.items(), page, size, total);
    }

    public record Page(List<AuditRecord> items, int page, int size, long total) {
    }

    public record PageResult(List<AuditRecord> items, List<Count> counts) {
    }

    public record Count(long total) {
    }
}
