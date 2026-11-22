package edu.northeastern.cs5010.backend.product;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProductRepository extends MongoRepository<ProductDocument, String> {
    List<ProductDocument> findByStatus(ProductStatus status);
}
