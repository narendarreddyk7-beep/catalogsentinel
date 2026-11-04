package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.catalogsentinel.domain.listing.ListingEventType;

import java.time.Instant;
import java.util.UUID;

/** One row of the {@code processed_events} idempotency ledger. */
public record ProcessedEventRecord(
        UUID eventId,
        long sourceId,
        String externalProductId,
        long listingId,
        ListingEventType eventType,
        String requestFingerprint,
        long requestedVersion,
        Long appliedVersion,
        IngestionOutcome outcome,
        String reason,
        IngestionPath ingestionPath,
        Long ingestionJobId,
        String correlationId,
        Instant occurredAt,
        Instant receivedAt,
        Instant processedAt) {

    public IngestionResult toResult(boolean duplicate) {
        return new IngestionResult(eventId, outcome, listingId, appliedVersion, duplicate, reason, processedAt);
    }
}
