package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.core.testsupport.MutableClock;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingEventMapperTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private final ListingEventMapper mapper = new ListingEventMapper(new MutableClock(NOW));

    private static ListingPayloadV1 payload(String title, String gtin) {
        return new ListingPayloadV1(title, "Acme", "A-1", "Audio", gtin, Map.of("color", "Black"));
    }

    @Test
    void mapsValidUpsert() {
        UUID id = UUID.randomUUID();
        IncomingListingEvent e = mapper.toDomain(new ListingEventV1(id, "LISTING_UPSERTED", 1, "acme", "SKU-1", 3L,
                NOW.minusSeconds(60), payload("Speaker", "036000291452"), null));
        assertThat(e.eventId()).isEqualTo(id);
        assertThat(e.type()).isEqualTo(ListingEventType.LISTING_UPSERTED);
        assertThat(e.content().gtin()).isEqualTo("00036000291452");
    }

    @Test
    void mapsValidWithdrawal() {
        IncomingListingEvent e = mapper.toDomain(new ListingEventV1(UUID.randomUUID(), "LISTING_WITHDRAWN", 1, "acme",
                "SKU-1", 4L, NOW, null, null));
        assertThat(e.type()).isEqualTo(ListingEventType.LISTING_WITHDRAWN);
        assertThat(e.content()).isNull();
    }

    @Test
    void reportsEveryViolationAtOnce() {
        ListingEventV1 bad = new ListingEventV1(null, "LISTING_CREATED", 2, "Bad Code", "", null,
                NOW.plusSeconds(3600), payload(" ", "123"), null);
        assertThatThrownBy(() -> mapper.toDomain(bad))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(ex -> assertThat(((DomainValidationException) ex).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactlyInAnyOrder("schemaVersion", "eventType", "sourceVersion", "occurredAt",
                                "payload.title", "payload.gtin", "eventId", "sourceCode", "externalProductId"));
    }

    @Test
    void invalidTitleIsReportedOnceWithoutSpuriousPayloadError() {
        ListingEventV1 bad = new ListingEventV1(UUID.randomUUID(), "LISTING_UPSERTED", 1, "acme", "SKU-1", 1L, NOW,
                payload("", null), null);
        assertThatThrownBy(() -> mapper.toDomain(bad))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(ex -> assertThat(((DomainValidationException) ex).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactly("payload.title"));
    }

    @Test
    void upsertWithoutPayloadAndWithdrawalWithPayloadAreRejected() {
        assertThatThrownBy(() -> mapper.toDomain(new ListingEventV1(UUID.randomUUID(), "LISTING_UPSERTED", 1, "acme",
                "SKU-1", 1L, NOW, null, null))).hasMessageContaining("payload is required");
        assertThatThrownBy(() -> mapper.toDomain(new ListingEventV1(UUID.randomUUID(), "LISTING_WITHDRAWN", 1, "acme",
                "SKU-1", 1L, NOW, payload("x", null), null))).hasMessageContaining("payload must be absent");
    }

    @Test
    void toleratesSmallClockSkew() {
        IncomingListingEvent e = mapper.toDomain(new ListingEventV1(UUID.randomUUID(), "LISTING_WITHDRAWN", 1, "acme",
                "SKU-1", 1L, NOW.plusSeconds(120), null, null));
        assertThat(e.occurredAt()).isAfter(NOW);
    }
}
