package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static io.catalogsentinel.core.testsupport.Events.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency properties of the ingestion transaction, exercised with real threads against real PostgreSQL.
 * A start latch releases all workers at once to maximize contention.
 */
class ConcurrentIngestionIT {

    private static final String SRC = "acme-market";
    private static final int THREADS = 8;
    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(SRC, "Acme Marketplace", SourceRole.MARKETPLACE, true);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Ten rounds of eight simultaneous deliveries of one event, each round on an empty database: exactly one effect
     * per round. Also prints how many deliveries lost the race on the ledger's primary key (the arbiter path)
     * instead of finding the committed row, which shows the race really happens; that count varies between runs,
     * so it is reported rather than asserted.
     */
    @Test
    void concurrentDeliveriesOfOneEventHaveExactlyOneEffect() throws Exception {
        int rounds = 10;
        int arbiterRetries = 0;
        for (int round = 0; round < rounds; round++) {
            setUp();
            ctx.metrics.clear();
            IncomingListingEvent event = upsert(SRC, "SKU-1", 1, "Kettle");
            List<Callable<IngestionResult>> tasks = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                tasks.add(() -> ctx.ingestionService.ingest(sync(event)));
            }
            List<IngestionResult> results = runConcurrently(tasks);

            assertThat(results).extracting(IngestionResult::outcome).containsOnly(IngestionOutcome.CREATED);
            assertThat(results).filteredOn(r -> !r.duplicate()).hasSize(1);
            assertThat(results).extracting(IngestionResult::listingId).containsOnly(results.get(0).listingId());
            assertThat(ctx.count("product_listings")).isEqualTo(1);
            assertThat(ctx.count("processed_events")).isEqualTo(1);
            assertThat(ctx.count("outbox_events")).isEqualTo(1);
            arbiterRetries += (int) ctx.metrics.retries.stream().filter("concurrent_duplicate"::equals).count();
        }
        System.out.printf("CONCURRENT-DELIVERY rounds=%d deliveriesPerRound=%d resolvedByPrimaryKeyArbiter=%d%n",
                rounds, THREADS, arbiterRetries);
    }

    @Test
    void concurrentCreationOfOneListingByDifferentEventsCreatesOneRow() throws Exception {
        List<Callable<IngestionResult>> tasks = new ArrayList<>();
        for (int v = 1; v <= THREADS; v++) {
            long version = v;
            tasks.add(() -> ctx.ingestionService.ingest(sync(upsert(SRC, "SKU-RACE", version, "Kettle v" + version))));
        }
        List<IngestionResult> results = runConcurrently(tasks);

        assertThat(ctx.count("product_listings")).isEqualTo(1);
        assertThat(ctx.count("processed_events")).isEqualTo(THREADS);
        assertThat(results).filteredOn(r -> r.outcome() == IngestionOutcome.CREATED).hasSize(1);
        long listingId = results.get(0).listingId();
        assertThat(ctx.listingQueryService.get(listingId).sourceVersion()).isEqualTo(THREADS);
        assertThat(ctx.listingQueryService.get(listingId).title()).isEqualTo("Kettle v" + THREADS);
    }

    @RepeatedTest(3)
    void shuffledConcurrentVersionsConvergeToTheHighestVersion() throws Exception {
        int versions = 40;
        List<IncomingListingEvent> events = new ArrayList<>();
        for (int v = 1; v <= versions; v++) {
            events.add(v == versions - 5 ? withdraw(SRC, "SKU-2", v) : upsert(SRC, "SKU-2", v, "Speaker rev " + v));
        }
        // Also deliver every event twice to combine reordering with duplicate delivery.
        List<IncomingListingEvent> deliveries = new ArrayList<>(events);
        deliveries.addAll(events);
        Collections.shuffle(deliveries, new Random(42));

        List<Callable<IngestionResult>> tasks = new ArrayList<>();
        for (IncomingListingEvent e : deliveries) {
            tasks.add(() -> ctx.ingestionService.ingest(sync(e)));
        }
        runConcurrently(tasks);

        var listing = ctx.listingQueryService.search(null, null, null, null, null, null, null,
                io.catalogsentinel.core.common.PageRequest.first(10)).items().get(0);
        assertThat(listing.sourceVersion()).isEqualTo(versions);
        assertThat(listing.title()).isEqualTo("Speaker rev " + versions);
        assertThat(ctx.count("product_listings")).isEqualTo(1);
        assertThat(ctx.count("processed_events")).as("each event id recorded once").isEqualTo(versions);
    }

    @Test
    void independentListingsIngestInParallel() throws Exception {
        List<Callable<IngestionResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String sku = "SKU-" + i;
            tasks.add(() -> ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), SRC, sku, 1, "Item " + sku,
                    "Acme", "M-" + sku, null, Map.of()))));
        }
        List<IngestionResult> results = runConcurrently(tasks);
        assertThat(results).extracting(IngestionResult::outcome).containsOnly(IngestionOutcome.CREATED);
        assertThat(ctx.count("product_listings")).isEqualTo(200);
        assertThat(ctx.count("outbox_events")).isEqualTo(200);
    }
}
