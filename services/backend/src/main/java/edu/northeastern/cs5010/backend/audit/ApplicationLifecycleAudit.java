package edu.northeastern.cs5010.backend.audit;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Records backend start and normal shutdown. */
@Component
public class ApplicationLifecycleAudit {
    private final AuditService audit;

    public ApplicationLifecycleAudit(AuditService audit) {
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        // Startup must not fail just because MongoDB is down; the failure path logs instead.
        try {
            audit.record(AuditEventType.APPLICATION_STARTED, ApplicationLifecycleAudit.class, null,
                    Map.of("javaVersion", System.getProperty("java.version")));
        } catch (RuntimeException ignored) {
            // AuditService already logged the failed write.
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stopping() {
        try {
            audit.record(AuditEventType.APPLICATION_STOPPING, ApplicationLifecycleAudit.class, null, Map.of());
        } catch (RuntimeException ignored) {
            // AuditService already logged the failed write.
        }
    }
}
