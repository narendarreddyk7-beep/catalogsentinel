package io.catalogsentinel.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published (via the transactional outbox) whenever a listing's catalog-relevant state changes.
 * Consumers re-run matching and gap evaluation for the listing. Delivery is at-least-once and unordered, so
 * consumers must be idempotent and should re-read current state rather than trusting the payload blindly;
 * {@code version} and {@code contentHash} let them detect that a newer change has superseded this one.
 * JSON Schema: {@code schemas/listing-changed.v1.json}.
 *
 * @param changeType one of {@code CREATED}, {@code UPDATED}, {@code WITHDRAWN}, {@code REACTIVATED}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingChangedV1(
        UUID eventId,
        Integer schemaVersion,
        Long listingId,
        String sourceCode,
        String externalProductId,
        Long version,
        String status,
        String changeType,
        String contentHash,
        Instant occurredAt,
        UUID causationEventId,
        String correlationId) {

    public static final int SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "LISTING_CHANGED";
}
