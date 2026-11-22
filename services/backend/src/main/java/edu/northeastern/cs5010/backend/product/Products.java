package edu.northeastern.cs5010.backend.product;

import java.time.Instant;

/** Request and response bodies of {@code /api/v1/products} (API A, docs/api_contracts.md). */
public final class Products {
    private Products() {
    }

    /**
     * Body of {@code POST} and {@code PUT}.
     *
     * @param initialLogCount          optional; the category default is stored when omitted
     * @param physiologicalStateAlpha0 optional; the category default is stored when omitted
     * @param temperatureSource        optional; links the product to a sensor's readings in one dataset
     * @param version                  required on {@code PUT}: the version the user edited
     */
    public record Request(String name, String category, Double initialLogCount, Double physiologicalStateAlpha0,
                          ProductDocument.TemperatureSource temperatureSource, String notes, Long version) {
    }

    public record Response(String id, String name, String category, double initialLogCount,
                           double physiologicalStateAlpha0, ProductDocument.TemperatureSource temperatureSource,
                           String notes, ProductStatus status, long version, Instant createdAt, Instant updatedAt) {

        static Response of(ProductDocument p) {
            return new Response(p.id(), p.name(), p.category(), p.initialLogCount(), p.physiologicalStateAlpha0(),
                    p.temperatureSource(), p.notes(), p.status(), p.version(), p.createdAt(), p.updatedAt());
        }
    }
}
