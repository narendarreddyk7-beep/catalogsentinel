package io.catalogsentinel.core.scenario;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.core.ingestion.IngestionRequest;
import io.catalogsentinel.core.ingestion.IngestionResult;
import io.catalogsentinel.core.ingestion.ProcessedEventRecord;
import io.catalogsentinel.core.listing.ListingRecord;
import io.catalogsentinel.core.matching.MatchQueryRepository.CandidateView;
import io.catalogsentinel.core.metrics.DashboardRepository.CoverageRow;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.core.review.ReviewService.DecisionResult;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The smoke test's scenario ({@code scripts/e2e/smoke-scenario.json}) run in-process against PostgreSQL with the services
 * the API and the worker use, so every outcome {@code scripts/smoke.sh} expects from a running stack is checked
 * against the real ingestion, matching, gap and review code by the Java build.
 *
 * <p>What differs from the deployed path: events are applied by the ingestion service directly (the API and the
 * worker call the same service; the queued path is recorded as {@code ASYNC_QUEUE} and redelivered once), the
 * change consumer is replaced by calling {@code MatchingService.matchListing} for each listing, and reconciliation
 * jobs run on the test thread instead of a poller. HTTP status codes are not asserted here; outcomes and error codes
 * that map to them are. Three run tokens show the expectations do not depend on the generated names and GTINs.
 */
class SmokeScenarioIT {

    private final CoreTestContext ctx = new CoreTestContext();
    private final Map<String, Long> listingIds = new LinkedHashMap<>();
    private Map<String, Object> scenario;
    private String root;
    private long targetId;

    @BeforeEach
    void setUp() {
        ctx.reset();
        listingIds.clear();
    }

    @Test
    void gtinDerivationMatchesThePythonImplementation() {
        // Same vectors as scripts/e2e/test_smoke.py: both implementations must derive identical GTINs.
        assertThat(SmokeScenario.gtinFor("qorvandel", "kettle")).isEqualTo("2264317892033");
        assertThat(SmokeScenario.gtinFor("zymbrastik", "drive")).isEqualTo("2138650499429");
        assertThat(SmokeScenario.gtinFor("plenturix", "bottle")).isEqualTo("2971467712868");
        assertThat(SmokeScenario.checkDigit("200000000001")).isEqualTo(5);
        assertThat(SmokeScenario.fill("{Brand} {BRAND} {root} {token}", "abcd")).isEqualTo("Abcd ABCD smoke abcd abcd");
    }

    /**
     * The worker applies change events concurrently and in no guaranteed order, so the scenario is also run with the
     * marketplace's change events handled before the reference catalog's.
     */
    @ParameterizedTest(name = "run token {0}, marketplace changes first: {1}")
    @CsvSource({"qorvandel, false", "zymbrastik, true", "plenturix, false"})
    void theSmokeScenarioBehavesAsScripted(String token, boolean marketChangesFirst) {
        scenario = SmokeScenario.instantiate(SmokeScenario.load(), token);
        root = (String) scenario.get("categoryRoot");

        registerSources();
        long bottle = runLifecycle();
        ingestCatalog(bottle);
        processChanges(marketChangesFirst);
        long candidateId = assertReviewCandidate();

        reconcile("smoke-" + token + "-reconcile-1");
        assertSameProducts();
        assertGapsAndCoverage(section("firstReconciliation"));

        decide(token, candidateId);
        reconcile("smoke-" + token + "-reconcile-2");
        assertGapsAndCoverage(section("secondReconciliation"));
    }

    // ------------------------------------------------------------------ steps

    private void registerSources() {
        map(scenario.get("sources")).forEach((key, value) -> {
            Map<String, Object> s = map(value);
            long id = ctx.sourceService.register((String) s.get("code"), (String) s.get("name"),
                    SourceRole.valueOf((String) s.get("role")), true).sourceId();
            if (key.equals("market")) {
                targetId = id;
            }
        });
        assertThatThrownBy(() -> ctx.sourceService.register(sourceCode("market"), "again", SourceRole.MARKETPLACE, true))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo("SOURCE_CODE_TAKEN"));
    }

    /** create, replay, reused id, stale, conflict, withdraw, stale after withdrawal, reactivate. */
    private long runLifecycle() {
        Map<String, Object> lifecycle = map(scenario.get("lifecycle"));
        Map<String, Object> listing = listing((String) lifecycle.get("listing"));
        Map<String, ListingEventV1> sent = new HashMap<>();
        Long listingId = null;
        for (Object o : list(lifecycle.get("steps"))) {
            Map<String, Object> step = map(o);
            String name = (String) step.get("name");
            Map<String, Object> expect = map(step.get("expect"));
            ListingEventV1 event;
            if (step.containsKey("replayOf")) {
                event = sent.get((String) step.get("replayOf"));
            } else {
                ListingEventV1 reused = step.containsKey("reuseEventIdOf") ? sent.get((String) step.get("reuseEventIdOf")) : null;
                UUID id = reused != null ? reused.eventId() : UUID.randomUUID();
                long version = step.containsKey("version") ? num(step.get("version")) : reused.sourceVersion();
                event = event(listing, id, version, (String) step.getOrDefault("eventType", ListingEventV1.UPSERTED),
                        (String) step.getOrDefault("titleSuffix", ""));
            }
            sent.put(name, event);
            String code = (String) expect.get("code");
            if ("EVENT_ID_REUSED".equals(code)) {
                ListingEventV1 e = event;
                assertThatThrownBy(() -> apply(e, IngestionPath.SYNC_API)).as(name)
                        .isInstanceOf(ConflictException.class)
                        .satisfies(x -> assertThat(((ConflictException) x).code()).isEqualTo(code));
                continue;
            }
            IngestionResult r = apply(event, IngestionPath.SYNC_API);
            assertThat(r.outcome().name()).as(name).isEqualTo(code != null ? code : expect.get("outcome"));
            if (expect.containsKey("appliedVersion")) {
                Object v = expect.get("appliedVersion");
                assertThat(r.appliedVersion()).as(name + " appliedVersion").isEqualTo(v == null ? null : num(v));
            }
            if (expect.containsKey("duplicate")) {
                assertThat(r.duplicate()).as(name + " duplicate").isEqualTo(expect.get("duplicate"));
            }
            assertThat(listingId == null || listingId == r.listingId()).as(name + " same listing").isTrue();
            listingId = r.listingId();
        }
        Map<String, Object> finalState = map(lifecycle.get("finalState"));
        ListingRecord record = ctx.listingQueryService.get(listingId);
        assertThat(record.status().name()).isEqualTo(finalState.get("status"));
        assertThat(record.sourceVersion()).isEqualTo(num(finalState.get("sourceVersion")));
        List<String> ledger = ctx.listingQueryService.history(listingId, 20).stream()
                .map(e -> e.outcome().name()).sorted().toList();
        assertThat(ledger).as("ledger outcomes").containsExactlyElementsOf(strings(lifecycle.get("ledgerOutcomes")));
        return listingId;
    }

    private void ingestCatalog(long lifecycleListing) {
        map(scenario.get("listings")).forEach((key, value) -> {
            Map<String, Object> l = map(value);
            String path = (String) l.get("path");
            if (path.equals("lifecycle")) {
                listingIds.put(key, lifecycleListing);
                return;
            }
            IngestionPath ingestionPath = path.equals("async") ? IngestionPath.ASYNC_QUEUE : IngestionPath.SYNC_API;
            ListingEventV1 event = event(l, UUID.randomUUID(), num(l.get("sourceVersion")), ListingEventV1.UPSERTED, "");
            IngestionResult first = apply(event, ingestionPath);
            assertThat(first.outcome().name()).as(key).isEqualTo("CREATED");
            if (Boolean.TRUE.equals(l.get("redeliver"))) {
                IngestionResult again = apply(event, ingestionPath);
                assertThat(again.duplicate()).as(key + " redelivery is a duplicate").isTrue();
                assertThat(again.listingId()).isEqualTo(first.listingId());
                List<ProcessedEventRecord> history = ctx.listingQueryService.history(first.listingId(), 20);
                assertThat(history).as(key + " ledger after redelivery").hasSize(1);
                assertThat(history.get(0).eventId()).isEqualTo(event.eventId());
            }
            listingIds.put(key, first.listingId());
        });
        for (String source : List.of("reference", "market")) {
            long expected = map(scenario.get("listings")).values().stream()
                    .filter(v -> source.equals(map(v).get("source"))).count();
            long actual = ctx.listingQueryService.search(ctx.sourceService.getByCode(sourceCode(source)).sourceId(),
                    ListingStatus.ACTIVE, null, null, null, null, null, new PageRequest(null, 200)).items().size();
            assertThat(actual).as("active listings of " + source + " (no duplicates)").isEqualTo(expected);
        }
    }

    /** What the change consumer does for each listing-changed event. */
    private void processChanges(boolean reversed) {
        List<Long> order = new ArrayList<>(listingIds.values());
        if (reversed) {
            java.util.Collections.reverse(order);
        }
        order.forEach(ctx.matchingService::matchListing);
        listingIds.forEach((key, id) -> assertThat(ctx.listingQueryService.get(id).canonicalProductId())
                .as(key + " linked after its change event").isNotNull());
    }

    private long assertReviewCandidate() {
        Map<String, Object> expect = map(section("reviewCandidate"));
        List<String> between = strings(expect.get("between"));
        long reference = listingIds.get(between.get(0));
        long market = listingIds.get(between.get(1));
        List<CandidateView> pending = ctx.matchQueryRepository.searchCandidates("PENDING", null, null, null, market,
                new PageRequest(null, 50)).items();
        assertThat(pending).as("pending candidates of " + between.get(1)).hasSize(1);
        CandidateView c = pending.get(0);
        assertThat(Set.of(c.left().listingId(), c.right().listingId())).containsExactlyInAnyOrder(reference, market);
        assertThat(c.decision()).isEqualTo(expect.get("decision"));
        assertThat(c.reviewStatus()).isEqualTo(expect.get("reviewStatus"));
        System.out.printf("SMOKE-SCENARIO review candidate %s score=%s%n", between, c.score());
        return c.candidateId();
    }

    private void reconcile(String idempotencyKey) {
        ReconciliationJob job = ctx.reconciliationService.request(sourceCode("market"), sourceCode("reference"), "smoke",
                idempotencyKey);
        assertThat(ctx.reconciliationService.request(sourceCode("market"), sourceCode("reference"), "smoke",
                idempotencyKey).jobId()).as("same idempotency key, same job").isEqualTo(job.jobId());
        ReconciliationJob done = ctx.reconciliationService.run(job.jobId());
        assertThat(done.status()).as(String.valueOf(done.errorMessage())).isEqualTo("SUCCEEDED");
    }

    private void assertSameProducts() {
        for (Object pair : list(section("sameProduct"))) {
            List<String> keys = strings(pair);
            Long first = product(keys.get(0));
            for (String key : keys) {
                assertThat(product(key)).as(keys + " converge on one canonical product").isEqualTo(first);
            }
        }
    }

    private void assertGapsAndCoverage(Object sectionValue) {
        Map<String, Object> expect = map(sectionValue);
        List<GapView> gaps = ctx.gapQueryRepository.search(targetId, null, null, null, null, root, null,
                new PageRequest(null, 200)).items();
        int live = 0;
        for (Object o : list(expect.get("gaps"))) {
            Map<String, Object> g = map(o);
            long product = product((String) g.get("reference"));
            String status = (String) g.get("status");
            List<GapView> matching = gaps.stream()
                    .filter(v -> v.canonicalProductId() == product && v.status().equals(status)).toList();
            assertThat(matching).as(g + " in " + describe(gaps)).hasSize(1);
            GapView gap = matching.get(0);
            if (g.containsKey("reason")) {
                assertThat(gap.reason()).as(g.toString()).isEqualTo(g.get("reason"));
            }
            if (g.containsKey("bestCandidate")) {
                assertThat(gap.bestCandidateListingId()).as(g.toString()).isEqualTo(listingIds.get((String) g.get("bestCandidate")));
            }
            if (g.containsKey("resolution")) {
                assertThat(gap.resolution()).as(g.toString()).isEqualTo(g.get("resolution"));
            }
            if (!status.equals("RESOLVED")) {
                live++;
            }
        }
        long liveActual = gaps.stream().filter(v -> !v.status().equals("RESOLVED")).count();
        assertThat(liveActual).as("live gaps under " + root + ": " + describe(gaps)).isEqualTo(live);
        for (String carried : strings(expect.get("carried"))) {
            long product = product(carried);
            assertThat(gaps.stream().filter(v -> v.canonicalProductId() == product && !v.status().equals("RESOLVED")))
                    .as(carried + " is carried, so it has no live gap").isEmpty();
        }
        Map<String, Object> coverage = map(expect.get("coverage"));
        List<CoverageRow> rows = ctx.dashboardRepository.coverage(targetId).stream()
                .filter(r -> r.category().equals(root)).toList();
        assertThat(rows).as("coverage row " + root).hasSize(1);
        CoverageRow row = rows.get(0);
        Map<String, Long> actual = new LinkedHashMap<>();
        actual.put("referenceProducts", row.referenceProducts());
        actual.put("carried", row.carried());
        actual.put("openGaps", row.openGaps());
        actual.put("confirmedGaps", row.confirmedGaps());
        actual.put("dismissedGaps", row.dismissedGaps());
        actual.put("notEvaluated", row.notEvaluated());
        Map<String, Long> expected = new LinkedHashMap<>();
        coverage.forEach((k, v) -> expected.put(k, num(v)));
        assertThat(actual).isEqualTo(expected);
    }

    private void decide(String token, long candidateId) {
        Map<String, Object> decisions = map(section("decisions"));
        long product = product((String) decisions.get("confirmGapOf"));
        GapView gap = ctx.gapQueryRepository.search(targetId, "OPEN", null, null, null, root, null,
                new PageRequest(null, 200)).items().stream().filter(g -> g.canonicalProductId() == product)
                .findFirst().orElseThrow();
        String gapKey = "smoke-" + token + "-confirm-gap";
        DecisionResult confirmed = ctx.reviewService.decideGap(gap.gapId(), "CONFIRM", "smoke", "smoke test", gapKey);
        assertThat(confirmed.changed()).isTrue();
        assertThat(confirmed.newStatus()).isEqualTo("CONFIRMED");
        assertThat(ctx.reviewService.decideGap(gap.gapId(), "CONFIRM", "smoke", "smoke test", gapKey).decisionId())
                .as("replayed gap decision").isEqualTo(confirmed.decisionId());
        List<ReviewDecision> history = ctx.reviewDecisionRepository.forGap(gap.gapId());
        assertThat(history.stream().filter(d -> Objects.equals(d.decisionId(), confirmed.decisionId())
                && d.decision().equals("CONFIRM") && d.reviewer().equals("smoke"))).hasSize(1);

        String matchKey = "smoke-" + token + "-approve-candidate";
        DecisionResult approved = ctx.reviewService.decideMatch(candidateId, "APPROVE", "smoke", "smoke test", matchKey);
        assertThat(approved.newStatus()).isEqualTo("APPROVED");
        assertThat(ctx.reviewService.decideMatch(candidateId, "APPROVE", "smoke", "smoke test", matchKey).decisionId())
                .as("replayed match decision").isEqualTo(approved.decisionId());
        CandidateView after = ctx.matchQueryRepository.getCandidate(candidateId);
        assertThat(after.reviewStatus()).isEqualTo("APPROVED");
        assertThat(after.reviewedBy()).isEqualTo("smoke");
        List<String> pair = strings(decisions.get("approveCandidate"));
        assertThat(product(pair.get(1))).as("approval links both listings").isEqualTo(product(pair.get(0)));
    }

    // ------------------------------------------------------------------ helpers

    private IngestionResult apply(ListingEventV1 event, IngestionPath path) {
        return ctx.ingestionService.ingest(new IngestionRequest(ctx.eventMapper.toDomain(event), path, "smoke-scenario",
                null, ctx.clock.instant()));
    }

    @SuppressWarnings("unchecked")
    private ListingEventV1 event(Map<String, Object> listing, UUID id, long version, String type, String titleSuffix) {
        ListingPayloadV1 payload = type.equals(ListingEventV1.WITHDRAWN) ? null : new ListingPayloadV1(
                listing.get("title") + titleSuffix, (String) listing.get("brand"), (String) listing.get("model"),
                (String) listing.get("category"), (String) listing.get("gtin"),
                (Map<String, String>) listing.get("attributes"));
        return new ListingEventV1(id, type, ListingEventV1.SCHEMA_VERSION, sourceCode((String) listing.get("source")),
                (String) listing.get("externalProductId"), version, Instant.parse((String) scenario.get("occurredAt")),
                payload, null);
    }

    private Long product(String listingKey) {
        return ctx.listingQueryService.get(listingIds.get(listingKey)).canonicalProductId();
    }

    private Map<String, Object> listing(String key) {
        return map(map(scenario.get("listings")).get(key));
    }

    private String sourceCode(String key) {
        return (String) map(map(scenario.get("sources")).get(key)).get("code");
    }

    private Object section(String key) {
        return map(scenario.get("expect")).get(key);
    }

    private static String describe(List<GapView> gaps) {
        List<String> out = new ArrayList<>();
        gaps.forEach(g -> out.add(g.gapId() + ":" + g.canonicalProductId() + ":" + g.status() + ":" + g.reason()
                + ":" + g.resolution()));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static List<?> list(Object o) {
        return (List<?>) o;
    }

    private static List<String> strings(Object o) {
        return list(o).stream().map(String::valueOf).toList();
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }
}
