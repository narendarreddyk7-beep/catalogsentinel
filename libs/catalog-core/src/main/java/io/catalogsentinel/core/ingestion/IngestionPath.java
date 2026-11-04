package io.catalogsentinel.core.ingestion;

/** How an event entered the platform; recorded for metrics and latency breakdowns. */
public enum IngestionPath {
    /** {@code POST /api/v1/listing-events}: applied within the HTTP request. */
    SYNC_API,
    /** {@code POST /api/v1/ingestion/events}: accepted with 202, applied by the worker from SQS. */
    ASYNC_QUEUE,
    /** Produced by the batch pipeline and applied by the worker from SQS. */
    BATCH
}
