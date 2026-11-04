package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.EventMetadataV1;
import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.core.testsupport.InMemoryQueueClient;
import io.catalogsentinel.core.testsupport.MutableClock;
import io.catalogsentinel.core.testsupport.TestJsonCodec;
import io.catalogsentinel.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryQueueClient queue = new InMemoryQueueClient(clock).createQueue("listing-events");
    private final TestJsonCodec json = new TestJsonCodec();
    private final AsyncIngestionService service =
            new AsyncIngestionService(new ListingEventMapper(clock), queue, "listing-events", json, clock);

    private static ListingEventV1 event(String sku) {
        return new ListingEventV1(UUID.randomUUID(), "LISTING_UPSERTED", 1, "acme", sku, 1L, NOW,
                new ListingPayloadV1("Item " + sku, "Acme", null, null, null, Map.of()),
                new EventMetadataV1(null, null, 7L, null));
    }

    @Test
    void enqueuesValidBatchWithStampedMetadataAndAttributes() {
        ListingEventV1 e = event("A");
        AsyncIngestionService.AcceptedBatch batch = service.accept(List.of(e, event("B")), "corr-1", "catalog-api");

        assertThat(batch.complete()).isTrue();
        assertThat(batch.acceptedEventIds()).hasSize(2).contains(e.eventId().toString());
        ListingEventV1 queued = json.read(queue.bodies("listing-events").get(0), ListingEventV1.class);
        assertThat(queued.metadata().correlationId()).isEqualTo("corr-1");
        assertThat(queued.metadata().producer()).isEqualTo("catalog-api");
        assertThat(queued.metadata().acceptedAt()).isEqualTo(NOW);
        assertThat(queued.metadata().ingestionJobId()).as("producer-supplied job id is preserved").isEqualTo(7L);
        assertThat(queue.attributes("listing-events").get(0))
                .containsEntry("eventType", "LISTING_UPSERTED")
                .containsEntry("eventId", e.eventId().toString())
                .containsEntry("correlationId", "corr-1");
    }

    @Test
    void rejectsWholeBatchWhenAnyEventIsInvalidAndEnqueuesNothing() {
        ListingEventV1 bad = new ListingEventV1(UUID.randomUUID(), "LISTING_UPSERTED", 1, "acme", "B", 1L, NOW,
                new ListingPayloadV1("", null, null, null, "123", null), null);
        assertThatThrownBy(() -> service.accept(List.of(event("A"), bad), "c", "p"))
                .isInstanceOf(DomainValidationException.class)
                .satisfies(ex -> assertThat(((DomainValidationException) ex).violations())
                        .extracting(DomainValidationException.Violation::field)
                        .containsExactlyInAnyOrder("events[1].payload.title", "events[1].payload.gtin"));
        assertThat(queue.size("listing-events")).isZero();
    }

    @Test
    void rejectsDuplicateEventIdsWithinBatchAndOversizedBatches() {
        ListingEventV1 e = event("A");
        assertThatThrownBy(() -> service.accept(List.of(e, e), "c", "p")).hasMessageContaining("events[1].eventId");
        List<ListingEventV1> many = new ArrayList<>();
        for (int i = 0; i <= AsyncIngestionService.MAX_BATCH; i++) {
            many.add(event("X" + i));
        }
        assertThatThrownBy(() -> service.accept(many, "c", "p")).hasMessageContaining("at most 500");
        assertThatThrownBy(() -> service.accept(List.of(), "c", "p")).hasMessageContaining("at least one");
    }

    @Test
    void reportsEnqueueFailuresSoTheClientCanRetry() {
        queue.failNextSends(1);
        AsyncIngestionService.AcceptedBatch batch = service.accept(List.of(event("A")), "c", "p");
        assertThat(batch.complete()).isFalse();
        assertThat(batch.failed()).hasSize(1);
    }
}
