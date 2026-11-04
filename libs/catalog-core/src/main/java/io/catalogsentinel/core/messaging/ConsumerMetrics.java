package io.catalogsentinel.core.messaging;

import java.time.Duration;

/** Metrics port for queue consumers; applications bind it to Micrometer. */
public interface ConsumerMetrics {

    ConsumerMetrics NOOP = new ConsumerMetrics() {
        @Override
        public void received(String queue, int count) {
        }

        @Override
        public void completed(String queue, String outcome, Duration processingTime) {
        }

        @Override
        public void paused(String queue, Duration pause) {
        }
    };

    void received(String queue, int count);

    /** @param outcome {@code ack}, {@code retry}, {@code dead_letter} or {@code dead_letter_failed} */
    void completed(String queue, String outcome, Duration processingTime);

    /** Polling paused because a dependency looks unhealthy. */
    void paused(String queue, Duration pause);
}
