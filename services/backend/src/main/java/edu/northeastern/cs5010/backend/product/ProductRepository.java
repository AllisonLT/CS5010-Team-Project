package edu.northeastern.cs5010.backend.product;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRepository extends MongoRepository<ProductDocument, String> {
}
