package io.catalogsentinel.core.matching;

import io.catalogsentinel.contracts.ListingChangedV1;
import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.core.testsupport.CoreTestContext;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for stale match evidence: candidate decisions, canonical links and merges must follow the
 * listings' <em>current</em> content. A candidate is current only while both listings are ACTIVE and both
 * content hashes equal the hashes it was scored on (docs/adr/0013-match-evidence-currency.md).
 */
class StaleMatchEvidenceIT {

    private static final String REF = "ref-supply";
    private static final String MKT = "mkt-market";
    private static final String GTIN = "2000000000015";

    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(REF, "Reference Supply", SourceRole.REFERENCE, true);
        ctx.sourceService.register(MKT, "Market", SourceRole.MARKETPLACE, true);
    }

    // ------------------------------------------------------------------ fixtures

    private long ingest(String source, String externalId, long version, String title, String brand, String model,
                        String gtin, Map<String, String> attributes) {
        return ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), source, externalId, version, title, brand,
                model, gtin, attributes))).listingId();
    }

    /** Reference flash drive. */
    private long referenceDrive(String externalId, long version) {
        return ingest(REF, externalId, version, "Altora Pro AL-4520X 64GB USB Flash Drive, Black", "Altora",
                "AL-4520X", GTIN, Map.of("color", "Black", "capacity", "64 GB"));
    }

    /** The same drive as the marketplace words it: auto-matches the reference drive (shared GTIN). */
    private long marketDrive(String externalId, long version) {
        return ingest(MKT, externalId, version, "ALTORA AL4520X 64 GB Flash Drive Black", "Altora", "AL4520X", GTIN,
                Map.of("color", "Black"));
    }

    /** A different product with a different brand, model and no GTIN: never matches the drive. */
    private long lantern(String source, String externalId, long version) {
        return ingest(source, externalId, version, "Kestrel Trail KT-200 LED Camping Lantern, Green", "Kestrel",
                "KT-200", null, Map.of("color", "Green"));
    }

    /** Review band against the reference drive (no model), no match against the market drive (tier word). */
    private long reviewBandDrive(String externalId, long version) {
        return ingest(MKT, externalId, version, "Altora Pro USB Flash Drive 64GB Black", "Altora", null, null,
                Map.of("color", "Black"));
    }

    /** What the change consumer does for a listing event, then what every reconciliation does first. */
    private void processChange(long listingId) {
        ctx.matchingService.matchListing(listingId);
        ctx.matchingService.matchPending(null, 100, 1_000);
        ctx.matchingService.mergeDuplicateIdentities(100);
    }

    private void matchAll() {
        ctx.matchingService.matchPending(null, 100, 1_000);
        ctx.matchingService.mergeDuplicateIdentities(100);
    }

    private long productOf(long listingId) {
        long linked = ctx.jdbcTemplate.queryForObject(
                "SELECT canonical_product_id FROM listing_product_links WHERE listing_id = ?", Long.class, listingId);
        return ctx.canonicalProductRepository.resolveActive(linked);
    }

    private String productTitle(long productId) {
        return ctx.jdbcTemplate.queryForObject("SELECT title FROM canonical_products WHERE canonical_product_id = ?",
                String.class, productId);
    }

    private long candidateId(long a, long b) {
        return ctx.jdbcTemplate.queryForObject(
                "SELECT candidate_id FROM match_candidates WHERE left_listing_id = ? AND right_listing_id = ?",
                Long.class, Math.min(a, b), Math.max(a, b));
    }

    private String candidateStatus(long a, long b) {
        return ctx.jdbcTemplate.queryForObject(
                "SELECT review_status FROM match_candidates WHERE left_listing_id = ? AND right_listing_id = ?",
                String.class, Math.min(a, b), Math.max(a, b));
    }

    private int mergeablePairs() {
        return ctx.canonicalProductRepository.findMergeablePairs(100).size();
    }

    private List<GapView> openGaps() {
        return ctx.gapQueryRepository.search(null, "OPEN", null, null, null, null, new PageRequest(null, 200)).items();
    }

    private void reconcile() {
        var job = ctx.reconciliationService.request(MKT, null, "test", null);
        ctx.reconciliationService.run(job.jobId());
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    void changedListingLeavesTheSharedProductAndStaleEvidenceCannotRejoinIt() {
        long r1 = referenceDrive("R-1", 1);
        long m1 = marketDrive("M-1", 1);
        matchAll();
        long shared = productOf(r1);
        assertThat(productOf(m1)).isEqualTo(shared);

        lantern(MKT, "M-1", 2); // same marketplace SKU, now a different product, newer source version
        processChange(m1);

        assertThat(productOf(m1)).as("changed listing must not stay linked by old evidence").isNotEqualTo(shared);
        assertThat(productOf(r1)).as("the unchanged listing keeps its identity").isEqualTo(shared);
        assertThat(productTitle(shared)).contains("Flash Drive");
        assertThat(candidateStatus(r1, m1)).isIn("SUPERSEDED", "NOT_APPLICABLE");
        assertThat(mergeablePairs()).as("no merge may rejoin them").isZero();
        assertThat(ctx.matchingService.mergeDuplicateIdentities(100)).isZero();
        assertThat(productOf(m1)).isNotEqualTo(productOf(r1));

        // Gap evidence must not cite the stale candidate either.
        reconcile();
        List<GapView> gap = openGaps().stream().filter(g -> g.canonicalProductId() == shared).toList();
        assertThat(gap).hasSize(1);
        assertThat(gap.get(0).reason()).isEqualTo("NO_CANDIDATE");
        assertThat(gap.get(0).bestCandidateListingId()).isNull();
    }

    @Test
    void changedRepresentativeDoesNotRewriteTheProductOfUnchangedMembers() {
        long m1 = marketDrive("M-1", 1); // first seen: creates the product and represents it
        ctx.matchingService.matchListing(m1);
        long r1 = referenceDrive("R-1", 1);
        ctx.matchingService.matchListing(r1);
        long shared = productOf(m1);
        assertThat(productOf(r1)).isEqualTo(shared);

        lantern(MKT, "M-1", 2);
        processChange(m1);

        assertThat(productOf(r1)).isEqualTo(shared);
        assertThat(productTitle(shared)).as("the product still describes what its remaining member is")
                .contains("Flash Drive");
        assertThat(ctx.jdbcTemplate.queryForObject(
                "SELECT representative_listing_id FROM canonical_products WHERE canonical_product_id = ?", Long.class,
                shared)).isEqualTo(r1);
        assertThat(productOf(m1)).isNotEqualTo(shared);
        assertThat(productTitle(productOf(m1))).contains("Lantern");
        assertThat(mergeablePairs()).isZero();
    }

    @Test
    void withdrawalSupersedesEvidenceAndReactivationRebuildsItFromCurrentContent() {
        long r1 = referenceDrive("R-1", 1);
        long m1 = marketDrive("M-1", 1);
        matchAll();
        long shared = productOf(r1);

        ctx.ingestionService.ingest(sync(withdraw(MKT, "M-1", 2)));
        processChange(m1);

        assertThat(candidateStatus(r1, m1)).isEqualTo("SUPERSEDED");
        assertThat(ctx.matchCandidateRepository.linkableFor(r1)).isEmpty();
        assertThat(mergeablePairs()).isZero();
        reconcile();
        assertThat(openGaps()).filteredOn(g -> g.canonicalProductId() == shared).hasSize(1);

        marketDrive("M-1", 3); // reactivated with the original content
        processChange(m1);
        assertThat(candidateStatus(r1, m1)).isEqualTo("AUTO_ACCEPTED");
        assertThat(productOf(m1)).isEqualTo(productOf(r1));
        reconcile();
        assertThat(openGaps()).filteredOn(g -> g.canonicalProductId() == productOf(r1)).isEmpty();
    }

    @Test
    void humanApprovalIsSupersededWithAnAuditRecordWhenContentChanges() {
        long r1 = referenceDrive("R-1", 1);
        long m2 = reviewBandDrive("M-2", 1);
        matchAll();
        long candidate = candidateId(r1, m2);
        assertThat(candidateStatus(r1, m2)).isEqualTo("PENDING");
        ctx.reviewService.decideMatch(candidate, "APPROVE", "alice", "same drive, model missing on the listing", "k-1");
        assertThat(productOf(m2)).isEqualTo(productOf(r1));
        long shared = productOf(r1);

        lantern(MKT, "M-2", 2);
        // Before the change event is processed the stored decision describes old content: no new decision on it.
        assertThatThrownBy(() -> ctx.reviewService.decideMatch(candidate, "REJECT", "bob", "stale", "k-2"))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo("CANDIDATE_STALE"));

        processChange(m2);

        assertThat(candidateStatus(r1, m2)).isEqualTo("SUPERSEDED");
        assertThat(productOf(m2)).isNotEqualTo(shared);
        assertThat(productOf(r1)).isEqualTo(shared);
        List<ReviewDecision> trail = ctx.reviewDecisionRepository.forCandidate(candidate);
        assertThat(trail).extracting(ReviewDecision::decision).containsExactly("APPROVE", "SUPERSEDE");
        assertThat(trail.get(0).reviewer()).isEqualTo("alice");
        assertThat(trail.get(1).previousStatus()).isEqualTo("APPROVED");
        assertThat(trail.get(1).newStatus()).isEqualTo("SUPERSEDED");
        assertThat(trail.get(1).reviewer()).isEqualTo(MatchingService.SYSTEM_REVIEWER);
        assertThatThrownBy(() -> ctx.reviewService.decideMatch(candidate, "APPROVE", "carol", "again", "k-3"))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).code()).isEqualTo("CANDIDATE_NOT_REVIEWABLE"));
        assertThat(mergeablePairs()).isZero();
    }

    @Test
    void redeliveredChangeEventsAndRepeatedRunsChangeNothing() {
        long r1 = referenceDrive("R-1", 1);
        long m1 = marketDrive("M-1", 1);
        matchAll();
        ctx.reviewService.decideMatch(candidateId(r1, m1), "APPROVE", "alice", "checked", null);
        lantern(MKT, "M-1", 2);
        processChange(m1);
        long r1Product = productOf(r1);
        long m1Product = productOf(m1);
        long products = ctx.count("canonical_products");
        long audits = ctx.count("review_decisions");

        ListingChangedMessageHandler handler = new ListingChangedMessageHandler(ctx.json, ctx.matchingService);
        String body = ctx.json.write(new ListingChangedV1(UUID.randomUUID(), ListingChangedV1.SCHEMA_VERSION, m1, MKT,
                "M-1", 2L, "ACTIVE", "UPDATED", null, Instant.now(), UUID.randomUUID(), "redelivery"));
        for (int delivery = 1; delivery <= 3; delivery++) {
            handler.handle(new ReceivedMessage("msg-1", "rh-" + delivery, body, Map.of(), delivery, Instant.now()));
        }
        processChange(m1);
        processChange(r1);

        assertThat(productOf(r1)).isEqualTo(r1Product);
        assertThat(productOf(m1)).isEqualTo(m1Product);
        assertThat(ctx.count("canonical_products")).isEqualTo(products);
        assertThat(ctx.count("review_decisions")).as("supersession is audited once").isEqualTo(audits);
    }

    @Test
    void productHeldTogetherOnlyByAChangedListingSplitsIntoItsIndependentParts() {
        // Star: the reference drive matches the market drive (auto) and the review-band drive (human approval),
        // but those two do not match each other.
        long r1 = referenceDrive("R-1", 1);
        long m1 = marketDrive("M-1", 1);
        long m2 = reviewBandDrive("M-2", 1);
        matchAll();
        ctx.reviewService.decideMatch(candidateId(r1, m2), "APPROVE", "alice", "same drive", null);
        long hub = productOf(r1);
        assertThat(List.of(productOf(m1), productOf(m2))).containsOnly(hub);
        assertThat(candidateStatus(m1, m2)).isEqualTo("NOT_APPLICABLE");

        lantern(REF, "R-1", 2); // the hub changes
        processChange(r1);

        assertThat(productOf(r1)).isNotEqualTo(productOf(m1)).isNotEqualTo(productOf(m2));
        assertThat(productOf(m1)).as("listings joined only through the hub are no longer one product")
                .isNotEqualTo(productOf(m2));
        assertThat(List.of(productOf(m1), productOf(m2))).contains(hub);
        assertThat(mergeablePairs()).isZero();
        long linksPerProduct = ctx.jdbcTemplate.queryForObject("""
                SELECT max(n) FROM (SELECT count(*) AS n FROM listing_product_links GROUP BY canonical_product_id) x
                """, Long.class);
        assertThat(linksPerProduct).isEqualTo(1);
    }
}
