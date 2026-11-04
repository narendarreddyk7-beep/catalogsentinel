package io.catalogsentinel.worker.runtime;

import io.catalogsentinel.core.outbox.OutboxRelay;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives the transactional-outbox relay on a fixed delay and purges published rows after their retention period.
 *
 * <p>Every worker instance runs a relay; {@code FOR UPDATE SKIP LOCKED} in the claim query keeps instances from
 * publishing the same row in overlapping runs, so scaling out needs no leader election. The relay stops after the
 * consumers (lower phase), so change events written by the last in-flight messages are still published on a
 * graceful shutdown; anything left over is picked up by another instance or after restart.
 */
public class OutboxRelayScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private static final int PURGE_BATCH = 1_000;
    private static final int MAX_PURGE_BATCHES_PER_RUN = 50;

    private final OutboxRelay relay;
    private final OutboxRepository outbox;
    private final Clock clock;
    private final WorkerProperties.Outbox settings;
    private volatile ScheduledExecutorService executor;

    public OutboxRelayScheduler(OutboxRelay relay, OutboxRepository outbox, Clock clock, WorkerProperties.Outbox settings) {
        this.relay = relay;
        this.outbox = outbox;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-relay");
            t.setDaemon(true);
            return t;
        });
        long pollMs = Math.max(50, settings.pollInterval().toMillis());
        executor.scheduleWithFixedDelay(this::relayTick, 0, pollMs, TimeUnit.MILLISECONDS);
        long purgeMs = Math.max(60_000, settings.purgeInterval().toMillis());
        executor.scheduleWithFixedDelay(this::purgeTick, purgeMs, purgeMs, TimeUnit.MILLISECONDS);
        log.info("outbox relay started pollInterval={}ms batchSize={} retention={}", pollMs, settings.batchSize(),
                settings.retention());
    }

    /** One scheduling tick: relay until the backlog is drained or the per-tick bound is reached. */
    void relayTick() {
        try {
            relay.drain(settings.maxBatchesPerTick());
        } catch (RuntimeException e) {
            // Database or SQS unavailable: rows stay PENDING and the next tick retries.
            log.warn("outbox relay tick failed: {}", e.toString());
        }
    }

    void purgeTick() {
        try {
            int total = 0;
            for (int i = 0; i < MAX_PURGE_BATCHES_PER_RUN; i++) {
                int deleted = outbox.purgePublishedBefore(clock.instant().minus(settings.retention()), PURGE_BATCH);
                total += deleted;
                if (deleted < PURGE_BATCH) {
                    break;
                }
            }
            if (total > 0) {
                log.info("purged {} published outbox row(s) older than {}", total, settings.retention());
            }
        } catch (RuntimeException e) {
            log.warn("outbox purge failed: {}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService e = executor;
        if (e == null) {
            return;
        }
        executor = null;
        e.shutdown();
        try {
            if (!e.awaitTermination(10, TimeUnit.SECONDS)) {
                e.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            e.shutdownNow();
        }
        log.info("outbox relay stopped");
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public int getPhase() {
        return ConsumerLifecycle.PHASE - 100;
    }
}
