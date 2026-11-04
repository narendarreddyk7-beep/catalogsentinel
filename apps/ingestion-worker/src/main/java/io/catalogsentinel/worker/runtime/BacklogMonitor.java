package io.catalogsentinel.worker.runtime;

import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueNames;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * Samples SQS queue depths and the outbox backlog on a schedule and exposes them as gauges:
 *
 * <ul>
 *   <li>{@code catalog.queue.depth{queue, state=visible|in_flight|delayed}}</li>
 *   <li>{@code catalog.outbox.pending}, {@code catalog.outbox.failed},
 *       {@code catalog.outbox.oldest.pending.age} (seconds)</li>
 * </ul>
 *
 * Sampling is decoupled from scraping so a Prometheus or CloudWatch scrape never calls SQS or the database
 * synchronously. A value that could not be read is reported as NaN (dropped by most backends) rather than zero,
 * so "no data" never looks like "empty queue". In AWS, the SQS-native CloudWatch metrics
 * (ApproximateNumberOfMessagesVisible, ApproximateAgeOfOldestMessage) back the alarms; these gauges serve the
 * local stack and the dashboard.
 */
public final class BacklogMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BacklogMonitor.class);

    /** One queue's depth; {@code available=false} when it could not be read. */
    public record QueueSample(long visible, long inFlight, long delayed, boolean available, String error) {
        static QueueSample unavailable(String error) {
            return new QueueSample(0, 0, 0, false, error);
        }
    }

    /** Outbox backlog; {@code available=false} when the database could not be read. */
    public record OutboxSample(long pending, long failed, Duration oldestPendingAge, boolean available, String error) {
    }

    /** Immutable snapshot; {@code takenAt == null} before the first sample. */
    public record Snapshot(Instant takenAt, Map<String, QueueSample> queues, OutboxSample outbox) {
        static final Snapshot EMPTY = new Snapshot(null, Map.of(), new OutboxSample(0, 0, Duration.ZERO, false,
                "not sampled yet"));
    }

    private final QueueClient queue;
    private final List<String> queueNames;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final Duration interval;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile ScheduledExecutorService executor;

    public BacklogMonitor(QueueClient queue, QueueNames names, OutboxRepository outbox, Clock clock,
                          MeterRegistry registry, Duration interval) {
        this.queue = queue;
        this.queueNames = List.of(names.listingEvents(), names.listingEventsDlq(), names.changeEvents(),
                names.changeEventsDlq());
        this.outbox = outbox;
        this.clock = clock;
        this.interval = interval;
        for (String q : queueNames) {
            queueGauge(registry, q, "visible", QueueSample::visible);
            queueGauge(registry, q, "in_flight", QueueSample::inFlight);
            queueGauge(registry, q, "delayed", QueueSample::delayed);
        }
        Gauge.builder("catalog.outbox.pending", this, m -> outboxValue(m, OutboxSample::pending))
                .description("Outbox rows waiting to be published").register(registry);
        Gauge.builder("catalog.outbox.failed", this, m -> outboxValue(m, OutboxSample::failed))
                .description("Outbox rows that exhausted their publish attempts (operator action required)")
                .register(registry);
        Gauge.builder("catalog.outbox.oldest.pending.age", this,
                        m -> outboxValue(m, s -> s.oldestPendingAge().toMillis() / 1000.0))
                .description("Age of the oldest unpublished outbox row").baseUnit("seconds").register(registry);
    }

    private void queueGauge(MeterRegistry registry, String q, String state, ToDoubleFunction<QueueSample> value) {
        Gauge.builder("catalog.queue.depth", this, m -> {
                    QueueSample s = m.snapshot.queues().get(q);
                    return s == null || !s.available() ? Double.NaN : value.applyAsDouble(s);
                })
                .description("Approximate SQS queue depth")
                .tag("queue", q)
                .tag("state", state)
                .register(registry);
    }

    private static double outboxValue(BacklogMonitor m, ToDoubleFunction<OutboxSample> value) {
        OutboxSample s = m.snapshot.outbox();
        return s.available() ? value.applyAsDouble(s) : Double.NaN;
    }

    /** Takes a new sample now; failures are captured in the snapshot, never thrown. */
    public Snapshot refresh() {
        Map<String, QueueSample> queues = new LinkedHashMap<>();
        for (String q : queueNames) {
            try {
                QueueClient.QueueDepth d = queue.depth(q);
                queues.put(q, new QueueSample(d.visible(), d.inFlight(), d.delayed(), true, null));
            } catch (RuntimeException e) {
                queues.put(q, QueueSample.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        Instant now = clock.instant();
        OutboxSample outboxSample;
        try {
            OutboxRepository.OutboxStats stats = outbox.stats(now);
            outboxSample = new OutboxSample(stats.pending(), stats.failed(), stats.oldestPendingAge(), true, null);
        } catch (RuntimeException e) {
            outboxSample = new OutboxSample(0, 0, Duration.ZERO, false, e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        }
        Snapshot s = new Snapshot(now, Map.copyOf(queues), outboxSample);
        snapshot = s;
        return s;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backlog-monitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.warn("backlog sampling failed: {}", e.toString());
            }
        }, 0, Math.max(1000, interval.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
