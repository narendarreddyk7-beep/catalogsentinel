package io.catalogsentinel.worker.runtime;

import io.catalogsentinel.core.messaging.QueueConsumer;
import org.springframework.context.SmartLifecycle;

/**
 * Starts a consumer after the application context (datasource, metrics) is ready and stops it before the
 * connection pool closes, so in-flight messages can finish. Consumers stop in parallel: {@link #stop(Runnable)}
 * drains on its own thread and signals completion through the callback, so the shutdown budget
 * ({@code spring.lifecycle.timeout-per-shutdown-phase}) is shared rather than spent once per consumer.
 */
public class ConsumerLifecycle implements SmartLifecycle {

    /** Started last and stopped first among the worker's lifecycle beans. */
    public static final int PHASE = Integer.MAX_VALUE - 100;

    private final QueueConsumer consumer;
    private final boolean enabled;

    public ConsumerLifecycle(QueueConsumer consumer, boolean enabled) {
        this.consumer = consumer;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (enabled) {
            consumer.start();
        }
    }

    @Override
    public void stop() {
        consumer.stop();
    }

    @Override
    public void stop(Runnable callback) {
        Thread.ofVirtual().name("consumer-shutdown").start(() -> {
            try {
                consumer.stop();
            } finally {
                callback.run();
            }
        });
    }

    @Override
    public boolean isRunning() {
        return consumer.isRunning();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    public QueueConsumer consumer() {
        return consumer;
    }
}
