package io.catalogsentinel.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Worker configuration under {@code catalogsentinel.*}; defaults in application.yml.
 *
 * @param consumers      settings shared by both consumers (per-queue concurrency below)
 * @param outbox         relay polling and retry settings
 * @param backlogRefresh how often queue depths and outbox backlog are sampled for gauges and health
 */
@ConfigurationProperties(prefix = "catalogsentinel")
public record WorkerProperties(Aws aws, Queues queues, Consumers consumers, Outbox outbox, Duration transactionTimeout,
                               Duration backlogRefresh) {

    public record Aws(String region, String sqsEndpoint) {
    }

    public record Queues(String listingEvents, String listingEventsDlq, String changeEvents, String changeEventsDlq) {
    }

    /**
     * @param maxAttempts    receives before a retryable message is dead-lettered explicitly (keep below the queue's
     *                       redrive maxReceiveCount so the redrive policy stays a crash-only safety net)
     * @param outageThreshold consecutive dependency failures that pause polling
     */
    public record Consumers(boolean enabled, int listingConcurrency, int changeConcurrency, int maxAttempts,
                            Duration waitTime, Duration visibilityTimeout, Duration backoffBase, Duration backoffCap,
                            int outageThreshold, Duration outagePause, Duration shutdownGrace) {
    }

    /**
     * @param maxBatchesPerTick upper bound on batches relayed per poll, so one instance cannot monopolise a backlog
     * @param retention         how long PUBLISHED rows are kept (audit and replay window) before they are purged
     */
    public record Outbox(boolean enabled, Duration pollInterval, int batchSize, int maxBatchesPerTick, int maxAttempts,
                         Duration backoffBase, Duration backoffCap, Duration retention, Duration purgeInterval) {
    }
}
