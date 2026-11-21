package edu.northeastern.cs5010.backend.audit;

import edu.northeastern.cs5010.backend.api.ApiError;
import edu.northeastern.cs5010.backend.api.ApiException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {
    public record AuditPage(List<AuditRecord> items, int page, int size, long total,
                            List<AuditEventType> eventTypes) {
    }

    private final AuditRepository repository;

    public AuditController(AuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public AuditPage list(
            @RequestParam(required = false) AuditCategory category,
            @RequestParam(required = false) List<AuditEventType> eventType,
            @RequestParam(required = false) AuditRecord.Outcome outcome,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0) {
            throw invalid("page", "Page must be zero or greater.");
        }
        if (size < 1 || size > 100) {
            throw invalid("size", "Page size must be between 1 and 100.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from", "The start of the range must precede or equal the end.");
        }

        var catalog = Arrays.stream(AuditEventType.values())
                .filter(type -> category == null || type.category() == category).toList();
        var types = catalog.stream().filter(type -> eventType == null || eventType.contains(type)).toList();
        if (types.isEmpty()) {
            return new AuditPage(List.of(), page, size, 0, catalog);
        }
        Criteria criteria = new Criteria();
        if (types.size() < AuditEventType.values().length) {
            criteria = criteria.and("eventType").in(types.stream().map(Enum::name).toList());
        }
        if (outcome != null) {
            criteria = criteria.and("outcome").is(outcome.name());
        }
        if (from != null || to != null) {
            criteria = criteria.and("timestamp");
            if (from != null) {
                criteria.gte(Date.from(from));
            }
            if (to != null) {
                criteria.lte(Date.from(to));
            }
        }
        var categories = EnumSet.noneOf(AuditCategory.class);
        types.forEach(type -> categories.add(type.category()));
        AuditRepository.Page result = repository.page(criteria, categories, page, size);
        return new AuditPage(result.items(), result.page(), result.size(), result.total(), catalog);
    }

    @RequestMapping(path = {"", "/{id}"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void rejectModification(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of("GET", "HEAD", "OPTIONS"));
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.validation(message, List.of(new ApiError.FieldError(field, null, message)));
    }
}
