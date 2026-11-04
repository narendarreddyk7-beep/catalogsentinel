package io.catalogsentinel.core.messaging;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.ingestion.ListingEventMessageHandler;
import io.catalogsentinel.core.ingestion.ListingIngestionService;
import io.catalogsentinel.core.ingestion.ProcessedEventRepository;
import io.catalogsentinel.core.listing.ListingRepository;
import io.catalogsentinel.core.matching.ListingChangedMessageHandler;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.outbox.OutboxRelay;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.core.testsupport.FlakyDataSource;
import io.catalogsentinel.core.testsupport.InMemoryQueueClient;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The asynchronous ingestion path end to end, with an SQS-faithful in-memory queue and a real database:
 * queue -> consumer -> ingestion engine -> outbox -> relay -> change queue -> matching consumer.
 */
class AsyncPipelineIT {

    private static final String EVENTS = "catalog-listing-events";
    private static final String EVENTS_DLQ = "catalog-listing-events-dlq";
    private static final String CHANGES = "catalog-change-events";
    private static final String CHANGES_DLQ = "catalog-change-events-dlq";

    private final CoreTestContext ctx = new CoreTestContext();
    private InMemoryQueueClient queue;
    private QueueConsumer listingConsumer;

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register("acme-market", "Acme", SourceRole.MARKETPLACE, true);
        queue = new InMemoryQueueClient(ctx.clock).withRedrive(EVENTS, EVENTS_DLQ, 8).withRedrive(CHANGES, CHANGES_DLQ, 8);
        listingConsumer = consumerFor(new ListingEventMessageHandler(ctx.json, ctx.eventMapper, ctx.ingestionService),
                EVENTS, EVENTS_DLQ, 5);
    }

    private QueueConsumer consumerFor(MessageHandler handler, String q, String dlq, int maxAttempts) {
        return new QueueConsumer(queue, handler, new QueueConsumer.Settings(q, dlq, 10, maxAttempts,
                Duration.ZERO, Duration.ofSeconds(30), BackoffPolicy.fullJitter(Duration.ofSeconds(1), Duration.ofSeconds(30)),
                3, Duration.ofMillis(10), Duration.ofSeconds(5)), ConsumerMetrics.NOOP, () -> true);
    }

    private ListingEventV1 event(UUID id, String sku, long version, String title) {
        return new ListingEventV1(id, "LISTING_UPSERTED", 1, "acme-market", sku, version,
                Instant.parse("2026-05-01T00:00:00Z"), new ListingPayloadV1(title, "Acme", "AC-" + sku, "Home", null,
                Map.of()), null);
    }

    private void enqueue(ListingEventV1 e) {
        queue.send(EVENTS, List.of(OutboundMessage.of(e.eventId().toString(), ctx.json.write(e),
                Map.of(MessageAttributes.EVENT_ID, e.eventId().toString()))));
    }

    private void drain(QueueConsumer c) {
        for (int i = 0; i < 200 && c.pollOnce() > 0; i++) {
            // keep polling until the queue yields nothing visible
        }
    }

    @Test
    void duplicateAndOutOfOrderDeliveryHaveExactlyOnceEffects() {
        List<ListingEventV1> events = new ArrayList<>();
        for (int sku = 0; sku < 20; sku++) {
            for (int v = 1; v <= 3; v++) {
                events.add(event(UUID.randomUUID(), "SKU-" + sku, v, "Item " + sku + " rev " + v));
            }
        }
        List<ListingEventV1> deliveries = new ArrayList<>(events);
        deliveries.addAll(events.subList(0, 30)); // at-least-once: half the events are delivered twice
        Collections.shuffle(deliveries, new Random(7));
        deliveries.forEach(this::enqueue);

        drain(listingConsumer);

        assertThat(queue.size(EVENTS)).isZero();
        assertThat(queue.size(EVENTS_DLQ)).isZero();
        assertThat(ctx.count("product_listings")).isEqualTo(20);
        assertThat(ctx.count("processed_events")).isEqualTo(60);
        assertThat(ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_listings WHERE source_version = 3 AND title LIKE '% rev 3'", Long.class))
                .isEqualTo(20);
        assertThat(ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_events WHERE ingestion_path = 'ASYNC_QUEUE'", Long.class)).isEqualTo(60);
    }

    @Test
    void crashBeforeAcknowledgingIsRecoveredByRedeliveryWithoutDuplicateEffects() {
        ListingEventV1 e = event(UUID.randomUUID(), "SKU-1", 1, "Kettle");
        enqueue(e);
        // Simulate a worker that applied the event and crashed before deleting the message.
        var received = queue.receive(EVENTS, 1, Duration.ZERO, Duration.ofSeconds(30)).get(0);
        new ListingEventMessageHandler(ctx.json, ctx.eventMapper, ctx.ingestionService).handle(received);
        assertThat(ctx.count("processed_events")).isEqualTo(1);

        ctx.clock.advance(Duration.ofSeconds(31)); // visibility timeout expires -> redelivery
        drain(listingConsumer);
        assertThat(queue.size(EVENTS)).isZero();
        assertThat(ctx.count("processed_events")).isEqualTo(1);
        assertThat(ctx.count("product_listings")).isEqualTo(1);
        assertThat(ctx.count("outbox_events")).isEqualTo(1);
        assertThat(ctx.metrics.outcomes).contains("ASYNC_QUEUE:CREATED:duplicate");
    }

    @Test
    void databaseOutageIsRetriedNotDeadLetteredAndRecoversAfterwards() {
        FlakyDataSource flaky = new FlakyDataSource(ctx.dataSource);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(flaky);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(flaky));
        ListingIngestionService ingestion = new ListingIngestionService(new SourceRepository(jdbc),
                new ListingRepository(jdbc), new ProcessedEventRepository(jdbc), new OutboxRepository(jdbc, ctx.json),
                tx, ctx.json, ctx.clock, ctx.metrics);
        QueueConsumer consumer = consumerFor(new ListingEventMessageHandler(ctx.json, ctx.eventMapper, ingestion),
                EVENTS, EVENTS_DLQ, 5);
        for (int i = 0; i < 5; i++) {
            enqueue(event(UUID.randomUUID(), "SKU-" + i, 1, "Item " + i));
        }

        flaky.setDown(true);
        consumer.pollOnce();
        ctx.clock.advance(Duration.ofMinutes(1));
        consumer.pollOnce();
        assertThat(queue.size(EVENTS)).as("messages kept for retry").isEqualTo(5);
        assertThat(queue.size(EVENTS_DLQ)).isZero();

        flaky.setDown(false);
        ctx.clock.advance(Duration.ofMinutes(1));
        drain(consumer);
        assertThat(queue.size(EVENTS)).isZero();
        assertThat(ctx.count("product_listings")).isEqualTo(5);
    }

    @Test
    void poisonMessagesAreDeadLetteredAndFixableOnesCanBeReplayed() {
        queue.send(EVENTS, List.of(OutboundMessage.of("junk", "{not json", Map.of())));
        ListingEventV1 unknownSource = new ListingEventV1(UUID.randomUUID(), "LISTING_UPSERTED", 1, "late-source",
                "SKU-9", 1L, Instant.parse("2026-05-01T00:00:00Z"), new ListingPayloadV1("Widget", null, null, null,
                null, null), null);
        enqueue(unknownSource);
        drain(listingConsumer);
        assertThat(queue.size(EVENTS_DLQ)).isEqualTo(2);
        assertThat(queue.attributes(EVENTS_DLQ)).extracting(a -> a.get(MessageAttributes.FAILURE_REASON))
                .anySatisfy(r -> assertThat(r).startsWith("MALFORMED_JSON"))
                .anySatisfy(r -> assertThat(r).startsWith("UNKNOWN_SOURCE"));

        // Operator registers the missing source and replays the DLQ.
        ctx.sourceService.register("late-source", "Late Source", SourceRole.REFERENCE, true);
        DeadLetterService dlq = new DeadLetterService(queue, new io.catalogsentinel.core.messaging.QueueNames(
                EVENTS, EVENTS_DLQ, CHANGES, CHANGES_DLQ));
        DeadLetterService.ReplayResult replay = dlq.replay(EVENTS_DLQ, 10);
        assertThat(replay.replayed()).isEqualTo(2);
        assertThat(queue.attributes(EVENTS)).allSatisfy(a -> assertThat(a).containsEntry(MessageAttributes.REPLAY_COUNT, "1"));
        drain(listingConsumer);
        assertThat(ctx.count("product_listings")).isEqualTo(1);
        assertThat(queue.bodies(EVENTS_DLQ)).containsExactly("{not json");
    }

    @Test
    void outboxRelayPublishesChangesThatDriveMatching() {
        enqueue(event(UUID.randomUUID(), "SKU-1", 1, "Acme Kettle 1.7L Steel"));
        enqueue(event(UUID.randomUUID(), "SKU-2", 1, "Acme Kettle 1.7L Steel"));
        drain(listingConsumer);
        OutboxRelay relay = new OutboxRelay(ctx.outboxRepository, queue, CHANGES, ctx.tx, ctx.clock,
                BackoffPolicy.fullJitter(Duration.ofSeconds(1), Duration.ofSeconds(60)), 50, 3);
        assertThat(relay.drain(10)).isEqualTo(2);
        assertThat(queue.size(CHANGES)).isEqualTo(2);
        assertThat(ctx.jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED'", Long.class))
                .isEqualTo(2);

        QueueConsumer changes = consumerFor(new ListingChangedMessageHandler(ctx.json, ctx.matchingService), CHANGES,
                CHANGES_DLQ, 5);
        drain(changes);
        assertThat(queue.size(CHANGES)).isZero();
        assertThat(ctx.count("listing_product_links")).isEqualTo(2);
        assertThat(ctx.count("match_candidates")).isEqualTo(1);
    }

    @Test
    void relayFailuresBackOffAndEventuallyFailButCanBeRequeued() {
        enqueue(event(UUID.randomUUID(), "SKU-1", 1, "Kettle"));
        drain(listingConsumer);
        OutboxRelay relay = new OutboxRelay(ctx.outboxRepository, queue, CHANGES, ctx.tx, ctx.clock,
                BackoffPolicy.fullJitter(Duration.ofSeconds(1), Duration.ofSeconds(60)), 50, 2);
        queue.failNextSends(2);
        assertThat(relay.relayOnce().failed()).isEqualTo(1);
        assertThat(relay.relayOnce().published()).as("not due yet: backing off").isZero();
        ctx.clock.advance(Duration.ofMinutes(5));
        assertThat(relay.relayOnce().failed()).isEqualTo(1);
        assertThat(ctx.outboxRepository.stats(ctx.clock.instant()).failed()).isEqualTo(1);

        assertThat(ctx.outboxRepository.requeueFailed(ctx.clock.instant())).isEqualTo(1);
        assertThat(relay.relayOnce().published()).isEqualTo(1);
        assertThat(queue.size(CHANGES)).isEqualTo(1);
    }

    @Test
    void concurrentRelaysNeverPublishARowTwice() throws Exception {
        for (int i = 0; i < 120; i++) {
            enqueue(event(UUID.randomUUID(), "SKU-" + i, 1, "Item " + i));
        }
        drain(listingConsumer);
        OutboxRelay relay = new OutboxRelay(ctx.outboxRepository, queue, CHANGES, ctx.tx, ctx.clock,
                BackoffPolicy.fullJitter(Duration.ofSeconds(1), Duration.ofSeconds(60)), 10, 3);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> relay.drain(100));
            threads[t].start();
        }
        for (Thread t : threads) {
            t.join(30_000);
        }
        assertThat(queue.size(CHANGES)).isEqualTo(120);
        assertThat(queue.bodies(CHANGES)).doesNotHaveDuplicates();
    }
}
