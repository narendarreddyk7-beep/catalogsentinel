package io.catalogsentinel.domain.listing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.catalogsentinel.domain.listing.IngestionOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;

class ListingEventDeciderTest {

    private static final ListingContent CONTENT_A =
            ListingContent.of("Acme Kettle 1.7L", "Acme", "K-100", "Home > Kitchen", null, Map.of("color", "Black"));
    private static final ListingContent CONTENT_B =
            ListingContent.of("Acme Kettle 1.7L Steel", "Acme", "K-100", "Home > Kitchen", null, Map.of("color", "Black"));

    private static IncomingListingEvent upsert(long version, ListingContent content) {
        return new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_UPSERTED, "acme-supplier",
                "SKU-1", version, Instant.parse("2026-01-01T00:00:00Z"), content);
    }

    private static IncomingListingEvent withdraw(long version) {
        return new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_WITHDRAWN, "acme-supplier",
                "SKU-1", version, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private static ListingState active(long version, ListingContent content) {
        return new ListingState(version, ListingStatus.ACTIVE, content.contentHash());
    }

    private static ListingState withdrawn(long version, String hash) {
        return new ListingState(version, ListingStatus.WITHDRAWN, hash);
    }

    @Nested
    class NewListing {
        @Test
        void upsertCreatesActiveListing() {
            ListingDecision d = ListingEventDecider.decide(null, upsert(3, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(CREATED);
            assertThat(d.resultingStatus()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(d.resultingVersion()).isEqualTo(3);
            assertThat(d.replaceContent()).isTrue();
            assertThat(d.requiresWrite(null)).isTrue();
        }

        @Test
        void withdrawalOfUnseenListingRecordsTombstone() {
            ListingDecision d = ListingEventDecider.decide(null, withdraw(5));
            assertThat(d.outcome()).isEqualTo(WITHDRAWN);
            assertThat(d.resultingStatus()).isEqualTo(ListingStatus.WITHDRAWN);
            assertThat(d.resultingVersion()).isEqualTo(5);
            assertThat(d.replaceContent()).isFalse();
            assertThat(d.requiresWrite(null)).isTrue();
        }
    }

    @Nested
    class OrderingRules {
        @Test
        void olderVersionCannotOverwriteNewerData() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, upsert(4, CONTENT_B));
            assertThat(d.outcome()).isEqualTo(STALE_IGNORED);
            assertThat(d.resultingVersion()).isEqualTo(5);
            assertThat(d.requiresWrite(current)).isFalse();
        }

        @Test
        void newerVersionUpdatesContent() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, upsert(6, CONTENT_B));
            assertThat(d.outcome()).isEqualTo(UPDATED);
            assertThat(d.resultingVersion()).isEqualTo(6);
            assertThat(d.replaceContent()).isTrue();
        }

        @Test
        void newerVersionWithIdenticalContentOnlyAdvancesVersion() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, upsert(9, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(UNCHANGED);
            assertThat(d.resultingVersion()).isEqualTo(9);
            assertThat(d.replaceContent()).isFalse();
            assertThat(d.requiresWrite(current)).isTrue();
        }

        @Test
        void sameVersionIdenticalContentIsNoOp() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, upsert(5, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(UNCHANGED);
            assertThat(d.requiresWrite(current)).isFalse();
        }

        @Test
        void sameVersionConflictingContentIsRejected() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, upsert(5, CONTENT_B));
            assertThat(d.outcome()).isEqualTo(VERSION_CONFLICT);
            assertThat(d.outcome().accepted()).isFalse();
            assertThat(d.requiresWrite(current)).isFalse();
        }
    }

    @Nested
    class Tombstones {
        @Test
        void newerWithdrawalTombstonesActiveListing() {
            ListingState current = active(5, CONTENT_A);
            ListingDecision d = ListingEventDecider.decide(current, withdraw(6));
            assertThat(d.outcome()).isEqualTo(WITHDRAWN);
            assertThat(d.resultingStatus()).isEqualTo(ListingStatus.WITHDRAWN);
            assertThat(d.resultingVersion()).isEqualTo(6);
        }

        @Test
        void staleUpsertCannotResurrectWithdrawnListing() {
            ListingState current = withdrawn(6, CONTENT_A.contentHash());
            ListingDecision d = ListingEventDecider.decide(current, upsert(5, CONTENT_B));
            assertThat(d.outcome()).isEqualTo(STALE_IGNORED);
            assertThat(d.resultingStatus()).isEqualTo(ListingStatus.WITHDRAWN);
        }

        @Test
        void sameVersionUpsertCannotResurrectWithdrawnListing() {
            ListingState current = withdrawn(6, CONTENT_A.contentHash());
            ListingDecision d = ListingEventDecider.decide(current, upsert(6, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(VERSION_CONFLICT);
        }

        @Test
        void newerUpsertReactivatesWithdrawnListing() {
            ListingState current = withdrawn(6, CONTENT_A.contentHash());
            ListingDecision d = ListingEventDecider.decide(current, upsert(7, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(REACTIVATED);
            assertThat(d.resultingStatus()).isEqualTo(ListingStatus.ACTIVE);
            assertThat(d.replaceContent()).isTrue();
        }

        @Test
        void tombstoneWithoutContentCanBeReactivated() {
            ListingState current = withdrawn(2, null);
            ListingDecision d = ListingEventDecider.decide(current, upsert(3, CONTENT_A));
            assertThat(d.outcome()).isEqualTo(REACTIVATED);
        }

        @Test
        void repeatedNewerWithdrawalAdvancesTombstoneVersion() {
            ListingState current = withdrawn(6, null);
            ListingDecision d = ListingEventDecider.decide(current, withdraw(8));
            assertThat(d.outcome()).isEqualTo(UNCHANGED);
            assertThat(d.resultingVersion()).isEqualTo(8);
            assertThat(d.requiresWrite(current)).isTrue();
        }

        @Test
        void withdrawalAtSameVersionAsActiveContentIsConflict() {
            ListingState current = active(6, CONTENT_A);
            assertThat(ListingEventDecider.decide(current, withdraw(6)).outcome()).isEqualTo(VERSION_CONFLICT);
        }

        @Test
        void outOfOrderDeliveryConvergesToLatestVersion() {
            // Source emitted: v1 create, v2 update, v3 withdraw. Delivery order: v3, v1, v2.
            ListingDecision w3 = ListingEventDecider.decide(null, withdraw(3));
            ListingState afterW3 = new ListingState(w3.resultingVersion(), w3.resultingStatus(), null);
            ListingDecision u1 = ListingEventDecider.decide(afterW3, upsert(1, CONTENT_A));
            ListingDecision u2 = ListingEventDecider.decide(afterW3, upsert(2, CONTENT_B));
            assertThat(u1.outcome()).isEqualTo(STALE_IGNORED);
            assertThat(u2.outcome()).isEqualTo(STALE_IGNORED);
            assertThat(afterW3.status()).isEqualTo(ListingStatus.WITHDRAWN);
            assertThat(afterW3.version()).isEqualTo(3);
        }
    }

    /** Exhaustive decision table: (current status, current version, event type, event version, same content). */
    @ParameterizedTest(name = "{0} v{1} <- {2} v{3} sameContent={4} => {5}")
    @CsvSource({
            "ACTIVE,5,UPSERT,4,true,STALE_IGNORED",
            "ACTIVE,5,UPSERT,4,false,STALE_IGNORED",
            "ACTIVE,5,UPSERT,5,true,UNCHANGED",
            "ACTIVE,5,UPSERT,5,false,VERSION_CONFLICT",
            "ACTIVE,5,UPSERT,6,true,UNCHANGED",
            "ACTIVE,5,UPSERT,6,false,UPDATED",
            "ACTIVE,5,WITHDRAW,4,false,STALE_IGNORED",
            "ACTIVE,5,WITHDRAW,5,false,VERSION_CONFLICT",
            "ACTIVE,5,WITHDRAW,6,false,WITHDRAWN",
            "WITHDRAWN,5,UPSERT,4,true,STALE_IGNORED",
            "WITHDRAWN,5,UPSERT,5,true,VERSION_CONFLICT",
            "WITHDRAWN,5,UPSERT,6,true,REACTIVATED",
            "WITHDRAWN,5,UPSERT,6,false,REACTIVATED",
            "WITHDRAWN,5,WITHDRAW,4,false,STALE_IGNORED",
            "WITHDRAWN,5,WITHDRAW,5,false,UNCHANGED",
            "WITHDRAWN,5,WITHDRAW,6,false,UNCHANGED",
    })
    void decisionTable(ListingStatus status, long currentVersion, String eventKind, long eventVersion,
                       boolean sameContent, IngestionOutcome expected) {
        ListingState current = new ListingState(currentVersion, status, CONTENT_A.contentHash());
        IncomingListingEvent event = eventKind.equals("UPSERT")
                ? upsert(eventVersion, sameContent ? CONTENT_A : CONTENT_B)
                : withdraw(eventVersion);
        assertThat(ListingEventDecider.decide(current, event).outcome()).isEqualTo(expected);
    }
}
