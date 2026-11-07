package edu.northeastern.cs5010.backend.audit;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

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
        return mongo.insert(record);
    }

    public List<AuditRecord> findNewestFirst(Criteria criteria, int page, int size) {
        Query query = new Query(criteria).with(Sort.by(Sort.Direction.DESC, "timestamp"))
                .skip((long) page * size).limit(size);
        return mongo.find(query, AuditRecord.class);
    }

    public long count(Criteria criteria) {
        return mongo.count(new Query(criteria), AuditRecord.class);
    }
}
