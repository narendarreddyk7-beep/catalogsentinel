package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.core.review.ReviewService.DecisionResult;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.core.testsupport.GoldenCatalogLoader;
import io.catalogsentinel.core.testsupport.GoldenCatalogLoader.Loaded;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.withdraw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GapReconciliationIT {

    private static final String TARGET = "acme-market";
    private final CoreTestContext ctx = new CoreTestContext();
    private Map<String, Loaded> loaded;

    @BeforeEach
    void setUp() {
        ctx.reset();
        loaded = GoldenCatalogLoader.load(ctx, RepoFiles.locate("tests/golden-matching/golden-listings.tsv"));
    }

    private ReconciliationJob reconcile() {
        ReconciliationJob job = ctx.reconciliationService.request(TARGET, null, "test", null);
        return ctx.reconciliationService.run(job.jobId());
    }

    private List<GapView> gaps(String status) {
        return ctx.gapQueryRepository.search(null, status, null, null, null, null, new PageRequest(null, 200)).items();
    }

    private Set<String> truthOfReferenceListing(List<GapView> gaps) {
        Map<Long, String> truthByListing = loaded.values().stream()
                .collect(Collectors.toMap(Loaded::listingId, Loaded::truthKey));
        return gaps.stream().map(g -> truthByListing.get(g.referenceListingId())).collect(Collectors.toSet());
    }

    @Test
    void detectsGapsAgainstGroundTruthAndIsIdempotent() {
        ReconciliationJob job = reconcile();
        assertThat(job.status()).isEqualTo("SUCCEEDED");

        Set<String> referenceTruths = loaded.values().stream().filter(l -> l.source().equals("ref"))
                .map(Loaded::truthKey).collect(Collectors.toSet());
        Set<String> carriedByTarget = loaded.values().stream().filter(l -> l.source().equals("mkt"))
                .map(Loaded::truthKey).collect(Collectors.toSet());
        Set<String> trueGaps = new HashSet<>(referenceTruths);
        trueGaps.removeAll(carriedByTarget);

        List<GapView> open = gaps("OPEN");
        List<GapView> confident = open.stream().filter(g -> g.confidence().doubleValue() >= 0.5).toList();
        Set<String> flagged = truthOfReferenceListing(open);
        Set<String> flaggedConfident = truthOfReferenceListing(confident);
        long tpAll = flagged.stream().filter(trueGaps::contains).count();
        long tpConfident = flaggedConfident.stream().filter(trueGaps::contains).count();
        System.out.printf(java.util.Locale.ROOT,
                "GAP-EVAL referenceProducts=%d trueGaps=%d openGaps=%d precisionAll=%.4f recallAll=%.4f "
                        + "confidentGaps=%d precisionConfident=%.4f recallConfident=%.4f stats=%s%n",
                referenceTruths.size(), trueGaps.size(), open.size(), (double) tpAll / flagged.size(),
                (double) tpAll / trueGaps.size(), confident.size(), (double) tpConfident / flaggedConfident.size(),
                (double) tpConfident / trueGaps.size(), job.statsJson());

        Map<Long, String> truthByListing = loaded.values().stream()
                .collect(Collectors.toMap(Loaded::listingId, Loaded::truthKey));
        confident.stream().filter(g -> !trueGaps.contains(truthByListing.get(g.referenceListingId())))
                .forEach(g -> System.out.println("GAP-FALSE-CONFIDENT " + truthByListing.get(g.referenceListingId())
                        + " reason=" + g.reason() + " confidence=" + g.confidence() + " best="
                        + g.bestCandidateTitle() + " score=" + g.bestCandidateScore()));
        assertThat((double) tpAll / trueGaps.size()).as("every true gap is surfaced").isEqualTo(1.0);
        // Floors below the measured values in docs/benchmarks (residual errors are matcher false negatives and a
        // deliberately wrong GTIN in the golden set).
        assertThat((double) tpConfident / flaggedConfident.size()).as("confident-gap precision").isGreaterThanOrEqualTo(0.75);
        assertThat((double) tpConfident / trueGaps.size()).as("confident-gap recall").isGreaterThanOrEqualTo(0.65);
        assertThat(open).allSatisfy(g -> assertThat(g.evidenceJson()).contains("\"explanation\""));
        assertThat(open).isSortedAccordingTo((a, b) -> b.priorityScore().compareTo(a.priorityScore()));

        Set<Long> before = open.stream().map(GapView::gapId).collect(Collectors.toSet());
        ReconciliationJob second = reconcile();
        assertThat(second.statsJson()).doesNotContain("gapsOpened");
        assertThat(gaps("OPEN").stream().map(GapView::gapId).collect(Collectors.toSet())).isEqualTo(before);
        assertThat(gaps("OPEN")).allSatisfy(g -> assertThat(g.detectionCount()).isEqualTo(2));
    }

    @Test
    void lifecycleTransitionsAreAuditedAndValidated() {
        reconcile();
        long gapId = gaps("OPEN").get(0).gapId();

        DecisionResult confirm = ctx.reviewService.decideGap(gapId, "confirm", "alice", "worth sourcing", "k-1");
        assertThat(confirm.newStatus()).isEqualTo("CONFIRMED");
        DecisionResult replay = ctx.reviewService.decideGap(gapId, "CONFIRM", "alice", "retry", "k-1");
        assertThat(replay.changed()).isFalse();
        assertThat(replay.decisionId()).isEqualTo(confirm.decisionId());
        assertThat(ctx.reviewService.decideGap(gapId, "CONFIRM", "bob", null, null).changed()).isFalse();
        assertThatThrownBy(() -> ctx.reviewService.decideGap(gapId, "REOPEN", "bob", null, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CONFIRMED");
        assertThatThrownBy(() -> ctx.reviewService.decideGap(gapId, "DISMISS", "bob", null, "k-1"))
                .isInstanceOf(ConflictException.class);

        ctx.reviewService.decideGap(gapId, "DISMISS", "bob", "brand not wanted", null);
        ctx.reviewService.decideGap(gapId, "REOPEN", "carol", "brand strategy changed", null);
        ctx.reviewService.decideGap(gapId, "RESOLVE", "carol", "listed manually", null);
        assertThatThrownBy(() -> ctx.reviewService.decideGap(gapId, "REOPEN", "carol", null, null))
                .isInstanceOf(ConflictException.class);

        List<ReviewDecision> trail = ctx.reviewDecisionRepository.forGap(gapId);
        assertThat(trail).extracting(ReviewDecision::decision).containsExactly("CONFIRM", "DISMISS", "REOPEN", "RESOLVE");
        assertThat(trail).extracting(ReviewDecision::newStatus).containsExactly("CONFIRMED", "DISMISSED", "OPEN", "RESOLVED");
        assertThat(ctx.gapQueryRepository.get(gapId).resolution()).isEqualTo("REVIEWER_RESOLVED");
    }

    @Test
    void auditTrailIsAppendOnlyAtTheDatabaseLevel() {
        reconcile();
        long gapId = gaps("OPEN").get(0).gapId();
        long decisionId = ctx.reviewService.decideGap(gapId, "CONFIRM", "alice", "worth sourcing", null).decisionId();

        // Even SQL that bypasses the application cannot rewrite or erase history.
        assertThatThrownBy(() -> ctx.jdbcTemplate.update(
                "UPDATE review_decisions SET reviewer = 'mallory' WHERE decision_id = ?", decisionId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> ctx.jdbcTemplate.update("DELETE FROM review_decisions WHERE decision_id = ?", decisionId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> ctx.jdbcTemplate.execute("TRUNCATE review_decisions"))
                .hasMessageContaining("append-only");
        assertThat(ctx.reviewDecisionRepository.forGap(gapId)).extracting(ReviewDecision::reviewer)
                .containsExactly("alice");
    }

    @Test
    void dismissalSuppressesRedetectionAndNewListingResolvesGapsAutomatically() {
        reconcile();
        List<GapView> open = gaps("OPEN");
        GapView dismissed = open.get(0);
        GapView toBeCovered = open.stream().filter(g -> g.referenceGtin() != null && g.gapId() != dismissed.gapId())
                .findFirst().orElseThrow();
        ctx.reviewService.decideGap(dismissed.gapId(), "DISMISS", "alice", "not a fit", null);

        // The target starts carrying a product: same GTIN as the reference listing.
        var reference = ctx.listingQueryService.get(toBeCovered.referenceListingId());
        ctx.ingestionService.ingest(sync(new io.catalogsentinel.domain.listing.IncomingListingEvent(UUID.randomUUID(),
                io.catalogsentinel.domain.listing.ListingEventType.LISTING_UPSERTED, TARGET, "NEW-SKU", 1,
                java.time.Instant.parse("2026-05-02T00:00:00Z"),
                ListingContent.of(reference.title(), reference.brand(), reference.model(), reference.category(),
                        reference.gtin(), reference.attributes()))));

        ReconciliationJob second = reconcile();
        assertThat(second.statsJson()).contains("gapsSuppressedByDismissal").contains("gapsResolved");
        assertThat(ctx.gapQueryRepository.get(dismissed.gapId()).status()).isEqualTo("DISMISSED");
        GapView covered = ctx.gapQueryRepository.get(toBeCovered.gapId());
        assertThat(covered.status()).isEqualTo("RESOLVED");
        assertThat(covered.resolution()).isEqualTo("AUTO_MATCH_FOUND");
        assertThat(ctx.reviewDecisionRepository.forGap(covered.gapId()))
                .extracting(ReviewDecision::reviewer).containsExactly(ReconciliationService.SYSTEM_REVIEWER);
    }

    @Test
    void approvingAPendingMatchClosesTheGapItExplained() {
        reconcile();
        GapView unconfirmed = gaps("OPEN").stream().filter(g -> g.reason().equals("UNCONFIRMED_CANDIDATE"))
                .findFirst().orElseThrow();
        long candidateId = ctx.matchQueryRepository.searchCandidates(null, null, null, null, unconfirmed.referenceListingId(),
                        new PageRequest(null, 50)).items().stream()
                .filter(c -> c.left().listingId() == unconfirmed.bestCandidateListingId()
                        || c.right().listingId() == unconfirmed.bestCandidateListingId())
                .findFirst().orElseThrow().candidateId();

        DecisionResult approved = ctx.reviewService.decideMatch(candidateId, "APPROVE", "alice", "same product", null);
        assertThat(approved.newStatus()).isEqualTo("APPROVED");
        assertThat(ctx.canonicalProductRepository.findLink(unconfirmed.referenceListingId()).orElseThrow().canonicalProductId())
                .isEqualTo(ctx.canonicalProductRepository.findLink(unconfirmed.bestCandidateListingId()).orElseThrow()
                        .canonicalProductId());

        reconcile();
        assertThat(ctx.gapQueryRepository.get(unconfirmed.gapId()).status()).isEqualTo("RESOLVED");
        assertThat(ctx.reviewDecisionRepository.forCandidate(candidateId)).hasSize(1);
    }

    @Test
    void withdrawnReferenceResolvesItsGap() {
        reconcile();
        GapView gap = gaps("OPEN").get(0);
        String externalId = loaded.entrySet().stream().filter(e -> e.getValue().listingId() == gap.referenceListingId())
                .findFirst().orElseThrow().getKey();
        // The product may have several reference listings; withdraw all of them.
        ctx.ingestionService.ingest(sync(withdraw("ref-supplier", externalId, 2)));
        ctx.jdbcTemplate.query("""
                SELECT l.external_product_id FROM listing_product_links k
                JOIN product_listings l ON l.listing_id = k.listing_id
                JOIN catalog_sources s ON s.source_id = l.source_id
                WHERE k.canonical_product_id = ? AND s.role = 'REFERENCE' AND l.status = 'ACTIVE'
                """, rs -> {
            ctx.ingestionService.ingest(sync(withdraw("ref-supplier", rs.getString(1), 2)));
        }, gap.canonicalProductId());
        reconcile();
        assertThat(ctx.gapQueryRepository.get(gap.gapId()).resolution()).isEqualTo("REFERENCE_WITHDRAWN");
    }

    @Test
    void onlyOneActiveReconciliationPerTarget() {
        ReconciliationJob first = ctx.reconciliationService.request(TARGET, null, "a", "idem-1");
        assertThat(ctx.reconciliationService.request(TARGET, null, "a", "idem-1").jobId()).isEqualTo(first.jobId());
        assertThatThrownBy(() -> ctx.reconciliationService.request(TARGET, null, "b", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("job " + first.jobId());
        ctx.reconciliationService.run(first.jobId());
        assertThat(ctx.reconciliationService.request(TARGET, null, "b", null).status()).isEqualTo("PENDING");
    }

    @Test
    void abandonedJobsAreRecovered() {
        // Since V6 an abandoned job is resumed rather than failed: its runner's lease expires and the job is
        // claimed again as its next attempt (ReconciliationJobDurabilityIT covers the other recovery cases).
        ReconciliationJob stuck = ctx.reconciliationService.request(TARGET, null, "a", null);
        ctx.reconciliationService.claimNext("crashed-runner").orElseThrow();
        ctx.jdbcTemplate.update("UPDATE reconciliation_jobs SET lease_expires_at = now() - interval '1 second' "
                + "WHERE job_id = ?", stuck.jobId());
        assertThatThrownBy(() -> ctx.reconciliationService.request(TARGET, null, "b", null))
                .as("the target stays reserved for the job being recovered").isInstanceOf(ConflictException.class);

        ReconciliationJob recovered = ctx.reconciliationService.run(stuck.jobId());

        assertThat(recovered.status()).isEqualTo("SUCCEEDED");
        assertThat(recovered.attempt()).isEqualTo(2);
        assertThat(ctx.reconciliationService.request(TARGET, null, "b", null).status()).isEqualTo("PENDING");
    }

    @Test
    void dashboardSummaryReflectsState() {
        reconcile();
        var summary = ctx.dashboardRepository.summary(java.time.Instant.now());
        assertThat(summary.listingsByStatus()).containsKey("ACTIVE");
        assertThat(summary.gapsByStatus().get("OPEN")).isPositive();
        assertThat(summary.lastReconciliation()).containsEntry("status", "SUCCEEDED");
        var processing = ctx.dashboardRepository.processing(java.time.Instant.now().plusSeconds(60),
                java.time.Duration.ofDays(3650));
        assertThat(processing.latencyByPath()).isNotEmpty();
    }
}
