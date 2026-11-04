package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.domain.listing.IngestionOutcome;

import java.time.Duration;

/**
 * Metrics port. Applications bind it to Micrometer; the core stays free of a metrics dependency.
 * Called only after the transaction commits, so rolled-back attempts are never counted as outcomes.
 */
public interface IngestionMetrics {

    IngestionMetrics NOOP = new IngestionMetrics() {
        @Override
        public void recordOutcome(IngestionPath path, IngestionOutcome outcome, boolean duplicate, Duration latency) {
        }

        @Override
        public void recordFailure(IngestionPath path, String errorType) {
        }

        @Override
        public void recordRetry(String reason) {
        }
    };

    /**
     * @param latency from {@link IngestionRequest#receivedAt()} until commit (end-to-end for async paths)
     */
    void recordOutcome(IngestionPath path, IngestionOutcome outcome, boolean duplicate, Duration latency);

    void recordFailure(IngestionPath path, String errorType);

    /** An internal retry of the ingestion transaction (concurrent duplicate or lock failure). */
    void recordRetry(String reason);
}
