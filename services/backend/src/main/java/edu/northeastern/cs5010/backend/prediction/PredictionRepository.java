package edu.northeastern.cs5010.backend.prediction;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PredictionRepository extends MongoRepository<PredictionDocument, String> {
}
