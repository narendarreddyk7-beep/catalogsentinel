package io.catalogsentinel.domain.listing;

import io.catalogsentinel.domain.common.CanonicalForm;
import io.catalogsentinel.domain.common.Violations;
import io.catalogsentinel.domain.source.SourceCode;

import java.time.Instant;
import java.util.UUID;

/**
 * A validated listing event, independent of transport (HTTP body or SQS message).
 *
 * @param eventId           producer-assigned unique id; the idempotency key
 * @param sourceCode        the source that owns the listing
 * @param externalProductId the source's own identifier for the listing
 * @param sourceVersion     monotonically increasing per listing at the source; ordering uses this, never
 *                          timestamps, because producer clocks are not trusted to be comparable
 * @param occurredAt        when the change happened at the source (informational, part of the fingerprint)
 * @param content           required for upserts, must be {@code null} for withdrawals
 */
public record IncomingListingEvent(
        UUID eventId,
        ListingEventType type,
        String sourceCode,
        String externalProductId,
        long sourceVersion,
        Instant occurredAt,
        ListingContent content) {

    public static final int MAX_EXTERNAL_ID = 128;

    public IncomingListingEvent {
        Violations v = new Violations();
        if (eventId == null) {
            v.add("eventId", "is required");
        }
        if (type == null) {
            v.add("eventType", "is required");
        }
        if (!SourceCode.isValid(sourceCode)) {
            v.add("sourceCode", "must be a valid source code");
        }
        v.requireText("externalProductId", externalProductId, MAX_EXTERNAL_ID);
        if (sourceVersion < 0) {
            v.add("sourceVersion", "must be >= 0");
        }
        if (occurredAt == null) {
            v.add("occurredAt", "is required");
        }
        if (type == ListingEventType.LISTING_UPSERTED && content == null) {
            v.add("payload", "is required for LISTING_UPSERTED");
        }
        if (type == ListingEventType.LISTING_WITHDRAWN && content != null) {
            v.add("payload", "must be absent for LISTING_WITHDRAWN");
        }
        v.throwIfAny();
    }

    /**
     * Fingerprint of everything that defines the request, excluding the event id itself and any
     * transport metadata (correlation ids, receive timestamps). Two deliveries of the same event yield the
     * same fingerprint; reusing an event id for different data yields a different one.
     */
    public String fingerprint() {
        CanonicalForm form = CanonicalForm.create()
                .field("type", type.name())
                .field("sourceCode", sourceCode)
                .field("externalProductId", externalProductId)
                .field("sourceVersion", sourceVersion)
                .field("occurredAt", occurredAt.toString());
        if (content != null) {
            content.writeTo(form);
        }
        return form.sha256Hex();
    }

    public String contentHash() {
        return content == null ? null : content.contentHash();
    }
}
