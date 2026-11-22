package edu.northeastern.cs5010.backend.product;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Product endpoints of API A. The controller only maps HTTP; rules live in {@link ProductService}. */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    public ResponseEntity<Products.Response> create(@RequestBody Products.Request request) {
        ProductDocument created = products.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id()))
                .body(Products.Response.of(created));
    }

    @GetMapping
    public List<Products.Response> list() {
        return products.list().stream().map(Products.Response::of).toList();
    }

    @GetMapping("/{id}")
    public Products.Response get(@PathVariable String id) {
        return Products.Response.of(products.get(id));
    }

    @PutMapping("/{id}")
    public Products.Response update(@PathVariable String id, @RequestBody Products.Request request) {
        return Products.Response.of(products.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        products.delete(id);
        return ResponseEntity.noContent().build();
    }
}
