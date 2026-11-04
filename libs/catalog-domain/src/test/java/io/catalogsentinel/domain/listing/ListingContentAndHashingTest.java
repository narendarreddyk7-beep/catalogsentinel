package io.catalogsentinel.domain.listing;

import io.catalogsentinel.domain.common.CanonicalForm;
import io.catalogsentinel.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingContentAndHashingTest {

    @Test
    void contentHashIgnoresWhitespaceAndAttributeOrder() {
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("color", "Black");
        ordered.put("storage", "256 GB");
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("storage", "256 GB");
        reversed.put("color", " Black ");
        ListingContent a = ListingContent.of("Phone X", "Acme", "PX-1", "Phones", null, ordered);
        ListingContent b = ListingContent.of("  Phone X ", " Acme", "PX-1", "Phones", "", reversed);
        assertThat(a.contentHash()).isEqualTo(b.contentHash()).hasSize(64);
    }

    @Test
    void contentHashChangesWhenAnyFieldChanges() {
        ListingContent base = ListingContent.of("Phone X", "Acme", "PX-1", "Phones", null, Map.of("color", "Black"));
        assertThat(base.contentHash())
                .isNotEqualTo(ListingContent.of("Phone X", "Acme", "PX-2", "Phones", null, Map.of("color", "Black")).contentHash())
                .isNotEqualTo(ListingContent.of("Phone X", "Acme", "PX-1", "Phones", null, Map.of("color", "Blue")).contentHash())
                .isNotEqualTo(ListingContent.of("Phone X", null, "PX-1", "Phones", null, Map.of("color", "Black")).contentHash());
    }

    @Test
    void canonicalFormIsInjectiveAcrossFieldBoundaries() {
        String ab = CanonicalForm.create().field("x", "ab").field("y", "c").sha256Hex();
        String bc = CanonicalForm.create().field("x", "a").field("y", "bc").sha256Hex();
        String nullVsEmpty1 = CanonicalForm.create().field("x", (String) null).sha256Hex();
        String nullVsEmpty2 = CanonicalForm.create().field("x", "~").sha256Hex();
        assertThat(ab).isNotEqualTo(bc);
        assertThat(nullVsEmpty1).isNotEqualTo(nullVsEmpty2);
        assertThat(CanonicalForm.create().field("m", new TreeMap<>(Map.of("a", "1"))).text())
                .isEqualTo("m={1\n  a=1:1\n}\n");
    }

    @Test
    void titleIsRequired() {
        assertThatThrownBy(() -> ListingContent.of("  ", "Acme", null, null, null, null))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("payload.title");
    }

    @Test
    void invalidGtinIsRejectedWithFieldName() {
        assertThatThrownBy(() -> ListingContent.of("Thing", null, null, null, "036000291453", null))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e -> assertThat(((DomainValidationException) e).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactly("payload.gtin"));
    }

    @Test
    void gtinIsCanonicalizedToGtin14() {
        ListingContent c = ListingContent.of("Thing", null, null, null, "0360-0029-1452", null);
        assertThat(c.gtin()).isEqualTo("00036000291452");
    }

    @Test
    void fingerprintExcludesEventIdButIncludesEverythingElse() {
        ListingContent content = ListingContent.of("Phone X", "Acme", "PX-1", "Phones", null, null);
        Instant t = Instant.parse("2026-03-01T10:00:00Z");
        IncomingListingEvent e1 = new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_UPSERTED,
                "acme", "SKU-1", 3, t, content);
        IncomingListingEvent e2 = new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_UPSERTED,
                "acme", "SKU-1", 3, t, content);
        IncomingListingEvent e3 = new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_UPSERTED,
                "acme", "SKU-1", 4, t, content);
        assertThat(e1.fingerprint()).isEqualTo(e2.fingerprint()).isNotEqualTo(e3.fingerprint());
    }

    @Test
    void eventValidationReportsAllViolations() {
        assertThatThrownBy(() -> new IncomingListingEvent(null, ListingEventType.LISTING_UPSERTED, "Bad Code",
                " ", -1, null, null))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(e -> assertThat(((DomainValidationException) e).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactlyInAnyOrder("eventId", "sourceCode", "externalProductId", "sourceVersion",
                                "occurredAt", "payload"));
    }

    @Test
    void withdrawalMustNotCarryPayload() {
        ListingContent content = ListingContent.of("Phone X", null, null, null, null, null);
        assertThatThrownBy(() -> new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_WITHDRAWN,
                "acme", "SKU-1", 1, Instant.now(), content))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("payload");
    }

    @ParameterizedTest
    @ValueSource(strings = {"036000291452", "0036000291452", "00036000291452", "96385074", "4006381333931",
            "0 36000 29145 2"})
    void validGtins(String raw) {
        assertThat(Gtin.isValid(raw)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"036000291453", "123", "abcdefghijkl", "00000000000000", "40063813339310"})
    void invalidGtins(String raw) {
        assertThat(Gtin.isValid(raw)).isFalse();
    }
}
