package io.catalogsentinel.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Listing event, schema version 1. The same contract is accepted synchronously
 * ({@code POST /api/v1/listing-events}), asynchronously ({@code POST /api/v1/ingestion/events}, then SQS)
 * and produced by the batch pipeline. JSON Schema: {@code schemas/listing-event.v1.json}.
 *
 * <p>Fields are intentionally loosely typed (boxed, strings for enums) so that malformed input reaches
 * validation and produces field-level errors instead of opaque deserialization failures. Unknown
 * properties are ignored (tolerant reader) so producers can add fields without breaking consumers.
 *
 * @param eventId           unique per event; the idempotency key
 * @param eventType         {@code LISTING_UPSERTED} or {@code LISTING_WITHDRAWN}
 * @param schemaVersion     must be {@code 1}
 * @param sourceCode        code of a registered, enabled catalog source
 * @param externalProductId the source's identifier for the listing
 * @param sourceVersion     monotonically increasing per listing at the source
 * @param occurredAt        source-side change time (ISO-8601)
 * @param payload           listing content; required for upserts, absent for withdrawals
 * @param metadata          optional transport metadata, excluded from idempotency fingerprints
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingEventV1(
        UUID eventId,
        String eventType,
        Integer schemaVersion,
        String sourceCode,
        String externalProductId,
        Long sourceVersion,
        Instant occurredAt,
        ListingPayloadV1 payload,
        EventMetadataV1 metadata) {

    public static final int SCHEMA_VERSION = 1;
    public static final String UPSERTED = "LISTING_UPSERTED";
    public static final String WITHDRAWN = "LISTING_WITHDRAWN";

    /** Returns a copy carrying the given metadata (used when the API forwards an event to SQS). */
    public ListingEventV1 withMetadata(EventMetadataV1 newMetadata) {
        return new ListingEventV1(eventId, eventType, schemaVersion, sourceCode, externalProductId, sourceVersion,
                occurredAt, payload, newMetadata);
    }
}
