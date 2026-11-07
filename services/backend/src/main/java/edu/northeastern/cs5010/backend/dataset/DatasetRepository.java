package edu.northeastern.cs5010.backend.dataset;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface DatasetRepository extends MongoRepository<DatasetDocument, String> {
    List<DatasetDocument> findByStatusInOrderByCreatedAtDesc(Collection<DatasetStatus> statuses);

    List<DatasetDocument> findByStatus(DatasetStatus status);

    boolean existsByStorageSha256AndStatus(String sha256, DatasetStatus status);
}
