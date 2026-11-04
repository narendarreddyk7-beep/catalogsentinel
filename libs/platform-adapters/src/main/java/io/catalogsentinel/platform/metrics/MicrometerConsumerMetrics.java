package io.catalogsentinel.platform.metrics;

import io.catalogsentinel.core.messaging.ConsumerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * {@code catalog.consumer.received{queue}}, {@code catalog.consumer.messages{queue,outcome}} with a processing-time
 * timer, and {@code catalog.consumer.pauses{queue}}.
 */
public final class MicrometerConsumerMetrics implements ConsumerMetrics {

    private final MeterRegistry registry;

    public MicrometerConsumerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void received(String queue, int count) {
        if (count > 0) {
            Counter.builder("catalog.consumer.received").tag("queue", queue).register(registry).increment(count);
        }
    }

    @Override
    public void completed(String queue, String outcome, Duration processingTime) {
        Timer.builder("catalog.consumer.messages")
                .description("Messages handled, by outcome, with processing time")
                .tag("queue", queue)
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(processingTime);
    }

    @Override
    public void paused(String queue, Duration pause) {
        Counter.builder("catalog.consumer.pauses").tag("queue", queue).register(registry).increment();
    }
}
