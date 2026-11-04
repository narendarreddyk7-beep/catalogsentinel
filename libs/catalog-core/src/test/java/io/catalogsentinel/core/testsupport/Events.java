package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.core.ingestion.IngestionRequest;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.listing.ListingEventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Builders for listing events in tests. */
public final class Events {

    public static final Instant T0 = Instant.parse("2026-05-01T00:00:00Z");

    private Events() {
    }

    public static IncomingListingEvent upsert(String source, String externalId, long version, String title) {
        return upsert(UUID.randomUUID(), source, externalId, version, title, null, null, null, Map.of());
    }

    public static IncomingListingEvent upsert(UUID eventId, String source, String externalId, long version,
                                              String title, String brand, String model, String gtin,
                                              Map<String, String> attributes) {
        return new IncomingListingEvent(eventId, ListingEventType.LISTING_UPSERTED, source, externalId, version,
                T0.plusSeconds(version), ListingContent.of(title, brand, model, "Electronics > Audio", gtin, attributes));
    }

    public static IncomingListingEvent withdraw(String source, String externalId, long version) {
        return withdraw(UUID.randomUUID(), source, externalId, version);
    }

    public static IncomingListingEvent withdraw(UUID eventId, String source, String externalId, long version) {
        return new IncomingListingEvent(eventId, ListingEventType.LISTING_WITHDRAWN, source, externalId, version,
                T0.plusSeconds(version), null);
    }

    public static IngestionRequest sync(IncomingListingEvent event) {
        return new IngestionRequest(event, IngestionPath.SYNC_API, "test-corr", null, T0);
    }

    public static ListingEventV1 contract(String source, String externalId, long version, String title) {
        return new ListingEventV1(UUID.randomUUID(), ListingEventV1.UPSERTED, 1, source, externalId, version,
                T0, new ListingPayloadV1(title, "Acme", "A-1", "Audio", null, Map.of("color", "Black")), null);
    }
}
