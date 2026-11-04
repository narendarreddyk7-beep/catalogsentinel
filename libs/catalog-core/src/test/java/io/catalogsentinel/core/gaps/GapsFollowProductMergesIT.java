package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gap follows its product through identity merges (docs/adr/0014-gaps-follow-product-merges.md). When a product
 * with a live gap is merged into another, the gap moves to the surviving product with its id, status and review
 * history, so a confirmation or dismissal is not silently replaced by a new OPEN gap, and a gap whose product is now
 * carried is resolved as carried. Only when the survivor already has its own live gap is the merged product's gap
 * resolved ({@code MERGED_PRODUCT}), with the conflict recorded in its audit trail.
 */
class GapsFollowProductMergesIT {

    private static final String REF = "ref-supply";
    private static final String REF_2 = "ref-second";
    private static final String MKT = "mkt-target";
    private static final String OTHER = "mkt-other";

    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(REF, "Reference", SourceRole.REFERENCE, true);
        ctx.sourceService.register(REF_2, "Second reference", SourceRole.REFERENCE, true);
        ctx.sourceService.register(MKT, "Target", SourceRole.MARKETPLACE, true);
        ctx.sourceService.register(OTHER, "Other marketplace", SourceRole.MARKETPLACE, true);
    }

    /** Has model and GTIN. */
    private long referenceDrive(String source) {
        return ingest(source, "REF-DRIVE", "Altora Pro AL-4520X 64GB USB Flash Drive, Black", "AL-4520X", "2000000000015");
    }

    /** No model, no GTIN: review band against {@link #referenceDrive}. */
    private long reviewBandDrive(String source) {
        return ingest(source, "RB-DRIVE", "Altora Pro USB Flash Drive 64GB Black", null, null);
    }

    private long ingest(String source, String externalId, String title, String model, String gtin) {
        long id = ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), source, externalId, 1, title, "Altora", model,
                gtin, Map.of("color", "Black")))).listingId();
        ctx.matchingService.matchListing(id); // what the change consumer does
        return id;
    }

    private long productOf(long listingId) {
        return ctx.listingQueryService.get(listingId).canonicalProductId();
    }

    private long candidateBetween(long a, long b) {
        return ctx.jdbcTemplate.queryForObject(
                "SELECT candidate_id FROM match_candidates WHERE left_listing_id = ? AND right_listing_id = ?",
                Long.class, Math.min(a, b), Math.max(a, b));
    }

    private ReconciliationJob reconcile() {
        ReconciliationJob job = ctx.reconciliationService.request(MKT, null, "test", null);
        ReconciliationJob done = ctx.reconciliationService.run(job.jobId());
        assertThat(done.status()).as(String.valueOf(done.errorMessage())).isEqualTo("SUCCEEDED");
        return done;
    }

    private List<GapView> gapsOfTarget() {
        long target = ctx.sourceService.getByCode(MKT).sourceId();
        return ctx.gapQueryRepository.search(target, null, null, null, null, null, new PageRequest(null, 200)).items();
    }

    private List<GapView> liveGaps() {
        return gapsOfTarget().stream().filter(g -> !g.status().equals("RESOLVED")).toList();
    }

    @Test
    void aGapWhoseProductIsMergedIntoACarriedProductIsResolvedAsCarried() {
        long market = reviewBandDrive(MKT);          // matched first: its product has the lower id and survives
        long reference = referenceDrive(REF);
        assertThat(productOf(reference)).isNotEqualTo(productOf(market));
        reconcile();
        GapView gap = liveGaps().get(0);
        assertThat(gap.reason()).isEqualTo("UNCONFIRMED_CANDIDATE");

        ctx.reviewService.decideMatch(candidateBetween(reference, market), "APPROVE", "alex", "same drive", null);
        assertThat(productOf(reference)).as("approval merged the reference product into the market's").isEqualTo(productOf(market));
        reconcile();

        GapView after = ctx.gapQueryRepository.get(gap.gapId());
        assertThat(after.status()).isEqualTo("RESOLVED");
        assertThat(after.resolution()).as("the target carries the product").isEqualTo("AUTO_MATCH_FOUND");
        assertThat(liveGaps()).isEmpty();
    }

    @Test
    void aDismissedGapKeepsSuppressingDetectionAfterItsProductIsMerged() {
        long other = reviewBandDrive(OTHER);        // another marketplace's listing: the surviving identity
        long reference = referenceDrive(REF);
        reconcile();
        GapView gap = liveGaps().get(0);
        ctx.reviewService.decideGap(gap.gapId(), "DISMISS", "alex", "not for this marketplace", null);

        ctx.reviewService.decideMatch(candidateBetween(reference, other), "APPROVE", "alex", "same drive", null);
        long survivor = productOf(reference);
        assertThat(survivor).isEqualTo(productOf(other)).isNotEqualTo(gap.canonicalProductId());
        ReconciliationJob second = reconcile();

        assertThat(liveGaps()).as("no new OPEN gap replaces the dismissal").hasSize(1);
        GapView moved = liveGaps().get(0);
        assertThat(moved.gapId()).as("same gap, history kept").isEqualTo(gap.gapId());
        assertThat(moved.status()).isEqualTo("DISMISSED");
        assertThat(moved.canonicalProductId()).isEqualTo(survivor);
        assertThat(second.statsJson()).contains("\"gapsFollowedMerge\": 1").contains("\"gapsSuppressedByDismissal\": 1");
        assertThat(ctx.reviewDecisionRepository.forGap(gap.gapId())).extracting(ReviewDecision::decision)
                .containsExactly("DISMISS");
    }

    @Test
    void aConfirmedGapStaysConfirmedAfterItsProductIsMerged() {
        long other = reviewBandDrive(OTHER);
        long reference = referenceDrive(REF);
        reconcile();
        GapView gap = liveGaps().get(0);
        ctx.reviewService.decideGap(gap.gapId(), "CONFIRM", "alex", "worth sourcing", null);

        ctx.reviewService.decideMatch(candidateBetween(reference, other), "APPROVE", "alex", "same drive", null);
        reconcile();

        assertThat(liveGaps()).hasSize(1);
        GapView moved = liveGaps().get(0);
        assertThat(moved.gapId()).isEqualTo(gap.gapId());
        assertThat(moved.status()).isEqualTo("CONFIRMED");
        assertThat(moved.canonicalProductId()).isEqualTo(productOf(reference));
        assertThat(moved.detectionCount()).as("refreshed by the second run").isEqualTo(2);
    }

    @ParameterizedTest(name = "merged product''s gap decision: {0}")
    @ValueSource(strings = {"CONFIRM", "DISMISS"})
    void whenTheSurvivorAlreadyHasAGapItIsKeptAndTheMergedProductsGapIsResolvedWithTheConflictRecorded(String decision) {
        long secondReference = reviewBandDrive(REF_2);   // survives (lower id); its own gap stays OPEN
        long reference = referenceDrive(REF);
        reconcile();
        assertThat(liveGaps()).hasSize(2);
        GapView survivorGap = liveGaps().stream().filter(g -> g.canonicalProductId() == productOf(secondReference))
                .findFirst().orElseThrow();
        GapView mergedGap = liveGaps().stream().filter(g -> g.canonicalProductId() == productOf(reference))
                .findFirst().orElseThrow();
        ctx.reviewService.decideGap(mergedGap.gapId(), decision, "alex", "reviewed", null);

        ctx.reviewService.decideMatch(candidateBetween(reference, secondReference), "APPROVE", "alex", "same drive", null);
        ReconciliationJob second = reconcile();

        assertThat(liveGaps()).extracting(GapView::gapId).containsExactly(survivorGap.gapId());
        assertThat(ctx.gapQueryRepository.get(survivorGap.gapId()).status()).isEqualTo("OPEN");
        GapView resolved = ctx.gapQueryRepository.get(mergedGap.gapId());
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolution()).isEqualTo("MERGED_PRODUCT");
        List<ReviewDecision> trail = ctx.reviewDecisionRepository.forGap(mergedGap.gapId());
        assertThat(trail).extracting(ReviewDecision::decision).containsExactly(decision, "RESOLVE");
        assertThat(trail.get(1).reason()).contains("gap " + survivorGap.gapId()).contains("not carried over");
        assertThat(second.statsJson()).contains("\"mergedGapConflicts\": 1");
    }
}
