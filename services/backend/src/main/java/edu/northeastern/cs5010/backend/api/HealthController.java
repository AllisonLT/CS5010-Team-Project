package edu.northeastern.cs5010.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public HealthResponse health() {
        return new HealthResponse("ok", "backend");
    }

    public record HealthResponse(String status, String service) {
    }
}
