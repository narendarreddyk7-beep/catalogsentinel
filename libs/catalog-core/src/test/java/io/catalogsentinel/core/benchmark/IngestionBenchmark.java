package io.catalogsentinel.core.benchmark;

import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.core.ingestion.IngestionRequest;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.listing.ListingEventType;
import io.catalogsentinel.domain.source.SourceRole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Measures the core ingestion engine (idempotency ledger + versioned upsert + outbox, one transaction per event)
 * and DB-backed matching against the configured PostgreSQL, on records of a synthetic export. Not a unit test:
 * run explicitly (scripts/benchmark.sh) and read the printed Markdown.
 *
 * <p>Args: {@code <file.jsonl>:<sourceCode>[,<file>:<source>...] <eventsPerSource> <threads> [matchListings]
 * [latencySamples] [reconcileTarget]}. With {@code latencySamples}, that many further records per source are then applied one at a
 * time, each followed by {@code matchListing} as the worker's change consumer does, and the time from event to
 * canonical link is reported: the processing part of event-to-catalog latency, without SQS, the outbox relay or
 * consumer polling. With {@code reconcileTarget} (a marketplace source code), one reconciliation of that target
 * against every reference source is then run and timed. Connection settings come from
 * {@code CATALOGSENTINEL_TEST_JDBC_URL}, {@code _USER} and {@code _PASSWORD}; the database must be migrated and is
 * emptied first. A HikariCP pool is used, as in the services.
 */
public final class IngestionBenchmark {

    private IngestionBenchmark() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String[] inputs = args[0].split(",");
        int events = Integer.parseInt(args[1]);
        int threads = Integer.parseInt(args[2]);
        int matchListings = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int latencySamples = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        String reconcileTarget = args.length > 5 ? args[5] : null;

        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(System.getenv("CATALOGSENTINEL_TEST_JDBC_URL"));
        poolConfig.setUsername(System.getenv().getOrDefault("CATALOGSENTINEL_TEST_JDBC_USER", "catalog"));
        poolConfig.setPassword(System.getenv().getOrDefault("CATALOGSENTINEL_TEST_JDBC_PASSWORD", "catalog_local_dev"));
        poolConfig.setMaximumPoolSize(threads + 2);
        HikariDataSource dataSource = new HikariDataSource(poolConfig);
        CoreTestContext ctx = new CoreTestContext(dataSource);
        ctx.reset();
        ObjectMapper mapper = new ObjectMapper();
        List<IncomingListingEvent> batch = new ArrayList<>();
        List<IncomingListingEvent> fresh = new ArrayList<>();
        for (String input : inputs) {
          Path file = Path.of(input.substring(0, input.lastIndexOf(':')));
          String source = input.substring(input.lastIndexOf(':') + 1);
          ctx.sourceService.register(source, source,
                  source.endsWith("market") ? SourceRole.MARKETPLACE : SourceRole.REFERENCE, true);
          int taken = 0;
          for (String line : Files.readAllLines(file)) {
            if (taken == events + latencySamples) {
                break;
            }
            Map<String, Object> r = mapper.readValue(line, Map.class);
            try {
                ListingContent content = ListingContent.of((String) r.get("title"), (String) r.get("brand"),
                        (String) r.get("model"), (String) r.get("category"), (String) r.get("gtin"),
                        (Map<String, String>) r.get("attributes"));
                (taken < events ? batch : fresh).add(new IncomingListingEvent(UUID.randomUUID(),
                        ListingEventType.LISTING_UPSERTED, source, (String) r.get("externalProductId"),
                        ((Number) r.get("sourceVersion")).longValue(), Instant.parse("2026-11-01T00:00:00Z"), content));
                taken++;
            } catch (RuntimeException invalid) {
                // planted invalid records are the batch validator's job, not this benchmark's
            }
          }
        }

        long[] latencies = new long[batch.size()];
        AtomicInteger next = new AtomicInteger();
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    int i;
                    while ((i = next.getAndIncrement()) < batch.size()) {
                        long t0 = System.nanoTime();
                        ctx.ingestionService.ingest(new IngestionRequest(batch.get(i), IngestionPath.BATCH, "bench",
                                null, Instant.now()));
                        latencies[i] = System.nanoTime() - t0;
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        System.out.printf(Locale.ROOT, "| ingest | %d events | %d threads | %.1f s | %.0f events/s | p50 %.2f ms | p95 %.2f ms | p99 %.2f ms |%n",
                batch.size(), threads, seconds, batch.size() / seconds, pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99));
        System.out.printf(Locale.ROOT, "| check | listings=%d processed_events=%d outbox=%d |%n", ctx.count("product_listings"),
                ctx.count("processed_events"), ctx.count("outbox_events"));

        // Replaying the same events must change nothing: every one is a duplicate resolved by the ledger.
        long dupStart = System.nanoTime();
        int duplicates = 0;
        for (int i = 0; i < Math.min(2000, batch.size()); i++) {
            if (ctx.ingestionService.ingest(new IngestionRequest(batch.get(i), IngestionPath.BATCH, "bench", null,
                    Instant.now())).duplicate()) {
                duplicates++;
            }
        }
        double dupSeconds = (System.nanoTime() - dupStart) / 1e9;
        System.out.printf(Locale.ROOT, "| redelivery | %d replayed | %d recognised as duplicates | %.0f events/s |%n",
                Math.min(2000, batch.size()), duplicates, Math.min(2000, batch.size()) / dupSeconds);

        if (matchListings > 0) {
            long m0 = System.nanoTime();
            int matched = ctx.matchingService.matchPending(null, 200, matchListings);
            double ms = (System.nanoTime() - m0) / 1e9;
            System.out.printf(Locale.ROOT, "| match | %d listings | %.1f s | %.0f listings/s | candidates=%d canonical=%d |%n",
                    matched, ms, matched / ms, ctx.count("match_candidates"), ctx.count("canonical_products"));
        }

        if (!fresh.isEmpty()) {
            long[] eventToLink = new long[fresh.size()];
            long l0 = System.nanoTime();
            for (int i = 0; i < fresh.size(); i++) {
                long t0 = System.nanoTime();
                long listingId = ctx.ingestionService.ingest(new IngestionRequest(fresh.get(i), IngestionPath.ASYNC_QUEUE,
                        "bench", null, Instant.now())).listingId();
                ctx.matchingService.matchListing(listingId);
                eventToLink[i] = System.nanoTime() - t0;
            }
            double ls = (System.nanoTime() - l0) / 1e9;
            Arrays.sort(eventToLink);
            System.out.printf(Locale.ROOT, "| event-to-link (in-process, sequential) | %d events | %.0f events/s | p50 %.2f ms | p95 %.2f ms | p99 %.2f ms | max %.2f ms |%n",
                    fresh.size(), fresh.size() / ls, pct(eventToLink, 0.50), pct(eventToLink, 0.95),
                    pct(eventToLink, 0.99), eventToLink[eventToLink.length - 1] / 1e6);
        }

        if (reconcileTarget != null) {
            long r0 = System.nanoTime();
            var job = ctx.reconciliationService.request(reconcileTarget, null, "bench", null);
            var done = ctx.reconciliationService.run(job.jobId());
            double rs = (System.nanoTime() - r0) / 1e9;
            System.out.printf(Locale.ROOT, "| reconcile | %s | %s | %.1f s | stats %s |%n", reconcileTarget, done.status(),
                    rs, done.statsJson());
        }
        dataSource.close();
    }

    private static double pct(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1e6;
    }
}
