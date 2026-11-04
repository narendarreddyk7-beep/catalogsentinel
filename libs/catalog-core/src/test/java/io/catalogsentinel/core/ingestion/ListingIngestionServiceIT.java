package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.ListingChangedV1;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.listing.ListingRecord;
import io.catalogsentinel.core.outbox.OutboxMessage;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static io.catalogsentinel.core.testsupport.Events.withdraw;
import static io.catalogsentinel.domain.listing.IngestionOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingIngestionServiceIT {

    private static final String SRC = "acme-market";
    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(SRC, "Acme Marketplace", SourceRole.MARKETPLACE, true);
    }

    private IngestionResult ingest(IncomingListingEvent event) {
        return ctx.ingestionService.ingest(sync(event));
    }

    private ListingRecord listing(long id) {
        return ctx.listingQueryService.get(id);
    }

    @Test
    void createsListingWithNormalizedFieldsAndOutboxEvent() {
        IngestionResult r = ingest(upsert(UUID.randomUUID(), SRC, "SKU-1", 1,
                "Sony WH-1000XM5 Wireless Headphones, Black", "Sony Corporation", "WH-1000XM5", "4548736132610",
                Map.of("Colour", "Black")));

        assertThat(r.outcome()).isEqualTo(CREATED);
        assertThat(r.duplicate()).isFalse();
        assertThat(r.appliedVersion()).isEqualTo(1L);
        ListingRecord l = listing(r.listingId());
        assertThat(l.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(l.normalizedBrand()).isEqualTo("sony");
        assertThat(l.normalizedModel()).isEqualTo("wh1000xm5");
        assertThat(l.normalizedTitle()).isEqualTo("sony wh1000xm5 wireless headphones black");
        assertThat(l.gtin()).isEqualTo("04548736132610");
        assertThat(l.attributes()).containsEntry("Colour", "Black");
        assertThat(l.extractedAttributes()).containsEntry("color", "black");
        assertThat(l.contentHash()).hasSize(64);

        List<OutboxMessage> outbox = ctx.tx.execute(s -> ctx.outboxRepository.claimDue(Instant.now().plusSeconds(3600), 10));
        assertThat(outbox).hasSize(1);
        ListingChangedV1 changed = ctx.json.read(outbox.get(0).payloadJson(), ListingChangedV1.class);
        assertThat(changed.changeType()).isEqualTo("CREATED");
        assertThat(changed.listingId()).isEqualTo(r.listingId());
        assertThat(changed.causationEventId()).isEqualTo(r.eventId());
        assertThat(outbox.get(0).headers()).containsEntry("correlationId", "test-corr");
    }

    @Test
    void exactReplayIsIdempotentAndReturnsStoredOutcome() {
        IncomingListingEvent event = upsert(SRC, "SKU-1", 1, "Kettle");
        IngestionResult first = ingest(event);
        IngestionResult replay = ingest(event);
        IngestionResult replay2 = ingest(event);

        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.outcome()).isEqualTo(CREATED);
        assertThat(replay.listingId()).isEqualTo(first.listingId());
        assertThat(replay2.processedAt()).isEqualTo(first.processedAt());
        assertThat(ctx.count("product_listings")).isEqualTo(1);
        assertThat(ctx.count("processed_events")).isEqualTo(1);
        assertThat(ctx.count("outbox_events")).isEqualTo(1);
        assertThat(ctx.metrics.outcomes).containsExactly("SYNC_API:CREATED", "SYNC_API:CREATED:duplicate",
                "SYNC_API:CREATED:duplicate");
    }

    @Test
    void reusingEventIdWithDifferentDataIsRejected() {
        UUID id = UUID.randomUUID();
        ingest(upsert(id, SRC, "SKU-1", 1, "Kettle", null, null, null, Map.of()));
        assertThatThrownBy(() -> ingest(upsert(id, SRC, "SKU-1", 1, "Kettle Pro", null, null, null, Map.of())))
                .isInstanceOf(EventIdReusedException.class)
                .hasMessageContaining(id.toString());
        assertThat(ctx.count("processed_events")).isEqualTo(1);
        assertThat(listing(1).title()).isEqualTo("Kettle");
    }

    @Test
    void newerVersionUpdatesAndOlderVersionIsIgnored() {
        long id = ingest(upsert(SRC, "SKU-1", 5, "Kettle v5")).listingId();
        IngestionResult newer = ingest(upsert(SRC, "SKU-1", 7, "Kettle v7"));
        IngestionResult older = ingest(upsert(SRC, "SKU-1", 6, "Kettle v6"));

        assertThat(newer.outcome()).isEqualTo(UPDATED);
        assertThat(older.outcome()).isEqualTo(STALE_IGNORED);
        assertThat(older.appliedVersion()).isNull();
        ListingRecord l = listing(id);
        assertThat(l.sourceVersion()).isEqualTo(7);
        assertThat(l.title()).isEqualTo("Kettle v7");
        assertThat(ctx.count("outbox_events")).isEqualTo(2);
    }

    @Test
    void sameVersionConflictingContentIsRejectedAndRecorded() {
        long id = ingest(upsert(SRC, "SKU-1", 3, "Kettle")).listingId();
        IngestionResult conflict = ingest(upsert(SRC, "SKU-1", 3, "Kettle (different)"));
        IngestionResult identical = ingest(upsert(SRC, "SKU-1", 3, "Kettle"));

        assertThat(conflict.outcome()).isEqualTo(VERSION_CONFLICT);
        assertThat(identical.outcome()).isEqualTo(UNCHANGED);
        assertThat(listing(id).title()).isEqualTo("Kettle");
        assertThat(ctx.count("processed_events")).isEqualTo(3);
        assertThat(ctx.count("outbox_events")).isEqualTo(1);
    }

    @Test
    void withdrawalCreatesTombstoneThatStaleEventsCannotResurrect() {
        long id = ingest(upsert(SRC, "SKU-1", 1, "Kettle")).listingId();
        assertThat(ingest(withdraw(SRC, "SKU-1", 2)).outcome()).isEqualTo(WITHDRAWN);
        assertThat(ingest(upsert(SRC, "SKU-1", 1, "Kettle")).outcome()).isEqualTo(STALE_IGNORED);
        assertThat(ingest(upsert(SRC, "SKU-1", 2, "Kettle")).outcome()).isEqualTo(VERSION_CONFLICT);

        ListingRecord l = listing(id);
        assertThat(l.status()).isEqualTo(ListingStatus.WITHDRAWN);
        assertThat(l.withdrawnAt()).isNotNull();
        assertThat(l.title()).as("content kept for audit").isEqualTo("Kettle");

        IngestionResult reactivated = ingest(upsert(SRC, "SKU-1", 3, "Kettle 2026 Edition"));
        assertThat(reactivated.outcome()).isEqualTo(REACTIVATED);
        ListingRecord back = listing(id);
        assertThat(back.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(back.withdrawnAt()).isNull();
        assertThat(back.title()).isEqualTo("Kettle 2026 Edition");
    }

    @Test
    void outOfOrderDeliveryConvergesToLatestSourceState() {
        // Emitted by the source as v1 create, v2 update, v3 withdraw; delivered as v3, v1, v2.
        IngestionResult w = ingest(withdraw(SRC, "SKU-9", 3));
        IngestionResult u1 = ingest(upsert(SRC, "SKU-9", 1, "Toaster"));
        IngestionResult u2 = ingest(upsert(SRC, "SKU-9", 2, "Toaster XL"));

        assertThat(w.outcome()).isEqualTo(WITHDRAWN);
        assertThat(u1.outcome()).isEqualTo(STALE_IGNORED);
        assertThat(u2.outcome()).isEqualTo(STALE_IGNORED);
        ListingRecord l = listing(w.listingId());
        assertThat(l.status()).isEqualTo(ListingStatus.WITHDRAWN);
        assertThat(l.sourceVersion()).isEqualTo(3);
        assertThat(l.title()).isNull();
        assertThat(ctx.count("outbox_events")).as("tombstone of an unseen listing changes nothing downstream").isZero();
    }

    @Test
    void newerVersionWithIdenticalContentAdvancesVersionWithoutDownstreamEvent() {
        long id = ingest(upsert(SRC, "SKU-1", 1, "Kettle")).listingId();
        IngestionResult r = ingest(upsert(SRC, "SKU-1", 4, "Kettle"));
        assertThat(r.outcome()).isEqualTo(UNCHANGED);
        assertThat(r.appliedVersion()).isEqualTo(4L);
        assertThat(listing(id).sourceVersion()).isEqualTo(4);
        assertThat(ctx.count("outbox_events")).isEqualTo(1);
        assertThat(ingest(upsert(SRC, "SKU-1", 3, "Kettle (old)")).outcome()).isEqualTo(STALE_IGNORED);
    }

    @Test
    void unknownAndDisabledSourcesAreUnprocessableAndLeaveNoTrace() {
        assertThatThrownBy(() -> ingest(upsert("ghost-source", "SKU-1", 1, "Kettle")))
                .isInstanceOf(UnprocessableException.class)
                .satisfies(e -> assertThat(((UnprocessableException) e).code()).isEqualTo("UNKNOWN_SOURCE"));
        ctx.sourceService.setEnabled(ctx.sourceService.getByCode(SRC).sourceId(), false);
        assertThatThrownBy(() -> ingest(upsert(SRC, "SKU-1", 1, "Kettle")))
                .isInstanceOf(UnprocessableException.class)
                .satisfies(e -> assertThat(((UnprocessableException) e).code()).isEqualTo("SOURCE_DISABLED"));
        assertThat(ctx.count("processed_events")).isZero();
        assertThat(ctx.count("product_listings")).isZero();
        assertThat(ctx.metrics.failures).hasSize(2);
    }

    @Test
    void replayStillSucceedsAfterSourceIsDisabled() {
        IncomingListingEvent event = upsert(SRC, "SKU-1", 1, "Kettle");
        ingest(event);
        ctx.sourceService.setEnabled(ctx.sourceService.getByCode(SRC).sourceId(), false);
        assertThat(ingest(event).duplicate()).isTrue();
    }

    @Test
    void unknownIngestionJobIsAValidationError() {
        IncomingListingEvent event = upsert(SRC, "SKU-1", 1, "Kettle");
        assertThatThrownBy(() -> ctx.ingestionService.ingest(
                new IngestionRequest(event, IngestionPath.BATCH, null, 999L, Instant.now())))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("metadata.ingestionJobId");
    }

    @Test
    void historyListsEveryProcessedEventNewestFirst() {
        long id = ingest(upsert(SRC, "SKU-1", 1, "Kettle")).listingId();
        ctx.clock.advance(java.time.Duration.ofSeconds(1));
        ingest(upsert(SRC, "SKU-1", 2, "Kettle 2"));
        ctx.clock.advance(java.time.Duration.ofSeconds(1));
        ingest(upsert(SRC, "SKU-1", 1, "Kettle 0"));
        List<ProcessedEventRecord> history = ctx.listingQueryService.history(id, 10);
        assertThat(history).extracting(ProcessedEventRecord::outcome).containsExactly(STALE_IGNORED, UPDATED, CREATED);
        assertThat(history).extracting(ProcessedEventRecord::ingestionPath).containsOnly(IngestionPath.SYNC_API);
    }
}
