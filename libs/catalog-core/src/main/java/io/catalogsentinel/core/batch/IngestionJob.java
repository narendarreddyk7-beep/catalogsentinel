package io.catalogsentinel.core.batch;

import java.time.Instant;

/**
 * A batch ingestion job as stored. {@code manifestJson} and {@code errorSummaryJson} are raw JSON text (object and
 * array respectively); counts are {@code null} until the batch reports them.
 */
public record IngestionJob(
        long ingestionJobId,
        String jobKey,
        long sourceId,
        String sourceCode,
        IngestionJobStatus status,
        String manifestJson,
        String inputUri,
        String outputUri,
        String rejectedUri,
        Long rowsTotal,
        Long rowsValid,
        Long rowsRejected,
        Long eventsPublished,
        String errorSummaryJson,
        int attempt,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Instant updatedAt) {

    /** Lifecycle: REGISTERED -> RUNNING -> PUBLISHED | FAILED; FAILED -> RUNNING is a restart (attempt + 1). */
    public enum IngestionJobStatus {
        REGISTERED, RUNNING, PUBLISHED, FAILED
    }
}
