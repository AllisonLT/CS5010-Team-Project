package edu.northeastern.cs5010.backend.product;

/** Products are soft-deleted so that past predictions and audit records still resolve. */
public enum ProductStatus {
    ACTIVE,
    DELETED
}
