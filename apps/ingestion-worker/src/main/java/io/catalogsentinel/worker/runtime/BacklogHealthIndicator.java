package io.catalogsentinel.worker.runtime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports whether the worker can reach its queues, based on the latest {@link BacklogMonitor} sample (never a
 * live SQS call per health request). DOWN when the source queues are unreadable or the sample is stale; backlog
 * figures are details only: a large backlog is a scaling or alerting concern, and failing health checks for it
 * would make the orchestrator restart healthy workers exactly when they are needed most.
 *
 * <p>Belongs to the readiness group, not liveness: losing SQS should not restart the container.
 */
public class BacklogHealthIndicator implements HealthIndicator {

    private final BacklogMonitor monitor;
    private final Clock clock;
    private final Duration maxSampleAge;

    public BacklogHealthIndicator(BacklogMonitor monitor, Clock clock, Duration maxSampleAge) {
        this.monitor = monitor;
        this.clock = clock;
        this.maxSampleAge = maxSampleAge;
    }

    @Override
    public Health health() {
        BacklogMonitor.Snapshot s = monitor.snapshot();
        if (s.takenAt() == null) {
            return Health.unknown().withDetail("reason", "no backlog sample yet").build();
        }
        Duration age = Duration.between(s.takenAt(), clock.instant());
        Map<String, Object> queues = new LinkedHashMap<>();
        boolean allReadable = true;
        for (var e : s.queues().entrySet()) {
            BacklogMonitor.QueueSample q = e.getValue();
            if (q.available()) {
                queues.put(e.getKey(), Map.of("visible", q.visible(), "inFlight", q.inFlight(), "delayed", q.delayed()));
            } else {
                allReadable = false;
                queues.put(e.getKey(), Map.of("error", String.valueOf(q.error())));
            }
        }
        Map<String, Object> outbox = s.outbox().available()
                ? Map.of("pending", s.outbox().pending(), "failed", s.outbox().failed(),
                "oldestPendingAgeSeconds", s.outbox().oldestPendingAge().toSeconds())
                : Map.of("error", String.valueOf(s.outbox().error()));
        Health.Builder builder = allReadable && age.compareTo(maxSampleAge) <= 0 ? Health.up() : Health.down();
        return builder.withDetail("sampledSecondsAgo", age.toSeconds())
                .withDetail("queues", queues)
                .withDetail("outbox", outbox)
                .build();
    }
}
