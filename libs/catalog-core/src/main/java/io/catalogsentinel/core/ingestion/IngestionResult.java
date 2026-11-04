package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.domain.listing.IngestionOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of ingesting one event.
 *
 * @param duplicate      {@code true} when this event id was processed before and the stored outcome is being
 *                       returned (exact replay); no state was changed by this call
 * @param appliedVersion listing version produced by the event, {@code null} if nothing was applied
 */
public record IngestionResult(
        UUID eventId,
        IngestionOutcome outcome,
        long listingId,
        Long appliedVersion,
        boolean duplicate,
        String reason,
        Instant processedAt) {
}
