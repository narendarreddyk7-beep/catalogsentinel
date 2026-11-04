package io.catalogsentinel.platform.metrics;

import io.catalogsentinel.core.ingestion.IngestionMetrics;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Micrometer binding of {@link IngestionMetrics}.
 *
 * <ul>
 *   <li>{@code catalog.ingestion.events{path,outcome,duplicate}}: counter of processed events</li>
 *   <li>{@code catalog.ingestion.latency{path}}: timer from acceptance to commit, with p50/p95/p99</li>
 *   <li>{@code catalog.ingestion.failures{path,error}}: events that could not be processed</li>
 *   <li>{@code catalog.ingestion.retries{reason}}: internal transaction retries</li>
 * </ul>
 * Tag values come from enums or exception class names, so cardinality stays bounded.
 */
public final class MicrometerIngestionMetrics implements IngestionMetrics {

    private final MeterRegistry registry;

    public MicrometerIngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordOutcome(IngestionPath path, IngestionOutcome outcome, boolean duplicate, Duration latency) {
        Counter.builder("catalog.ingestion.events")
                .description("Listing events processed, by path and outcome")
                .tag("path", path.name())
                .tag("outcome", outcome.name())
                .tag("duplicate", Boolean.toString(duplicate))
                .register(registry)
                .increment();
        if (!duplicate) {
            Timer.builder("catalog.ingestion.latency")
                    .description("Time from event acceptance to committed processing")
                    .tag("path", path.name())
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(latency);
        }
    }

    @Override
    public void recordFailure(IngestionPath path, String errorType) {
        Counter.builder("catalog.ingestion.failures")
                .description("Listing events that failed processing")
                .tag("path", path.name())
                .tag("error", errorType)
                .register(registry)
                .increment();
    }

    @Override
    public void recordRetry(String reason) {
        Counter.builder("catalog.ingestion.retries")
                .description("Internal retries of the ingestion transaction")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
