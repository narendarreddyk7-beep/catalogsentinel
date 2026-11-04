package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.core.ingestion.IngestionMetrics;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.domain.listing.IngestionOutcome;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures metric calls so tests can assert on them. */
public final class RecordingIngestionMetrics implements IngestionMetrics {

    public final List<String> outcomes = new CopyOnWriteArrayList<>();
    public final List<String> failures = new CopyOnWriteArrayList<>();
    public final List<String> retries = new CopyOnWriteArrayList<>();

    @Override
    public void recordOutcome(IngestionPath path, IngestionOutcome outcome, boolean duplicate, Duration latency) {
        outcomes.add(path + ":" + outcome + (duplicate ? ":duplicate" : ""));
    }

    @Override
    public void recordFailure(IngestionPath path, String errorType) {
        failures.add(path + ":" + errorType);
    }

    @Override
    public void recordRetry(String reason) {
        retries.add(reason);
    }

    public void clear() {
        outcomes.clear();
        failures.clear();
        retries.clear();
    }
}
