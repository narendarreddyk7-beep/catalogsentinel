package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.domain.listing.IncomingListingEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * A validated event plus transport context.
 *
 * @param receivedAt when the platform first accepted the event (HTTP receipt or SQS enqueue time); used for
 *                   end-to-end processing latency
 */
public record IngestionRequest(
        IncomingListingEvent event,
        IngestionPath path,
        String correlationId,
        Long ingestionJobId,
        Instant receivedAt) {

    public IngestionRequest {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
