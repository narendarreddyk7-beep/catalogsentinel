package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.core.testsupport.GoldenCatalogLoader;
import io.catalogsentinel.core.testsupport.GoldenCatalogLoader.Loaded;
import io.catalogsentinel.domain.evaluation.ConfusionMatrix;
import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matching against PostgreSQL with the golden development set: SQL candidate retrieval (GTIN, brand+model
 * prefix, pg_trgm), persisted candidates and canonical identities. Prints measured figures for
 * docs/benchmarks/matching.
 */
class MatchingServiceIT {

    private final CoreTestContext ctx = new CoreTestContext();
    private Map<String, Loaded> loaded;

    @BeforeEach
    void setUp() {
        ctx.reset();
        loaded = GoldenCatalogLoader.load(ctx, RepoFiles.locate("tests/golden-matching/golden-listings.tsv"));
        ctx.matchingService.matchPending(null, 100, 10_000);
        ctx.matchingService.mergeDuplicateIdentities(1_000);
    }

    @Test
    void sqlRetrievalFindsEveryTruePairAndClustersArePrecise() {
        List<Loaded> all = GoldenCatalogLoader.listings(loaded);
        Set<String> candidatePairs = new HashSet<>(ctx.jdbcTemplate.query(
                "SELECT left_listing_id || ':' || right_listing_id FROM match_candidates", (rs, i) -> rs.getString(1)));
        Map<Long, Long> canonicalOf = new HashMap<>();
        ctx.jdbcTemplate.query("SELECT listing_id, canonical_product_id FROM listing_product_links",
                rs -> {
                    canonicalOf.put(rs.getLong(1), rs.getLong(2));
                });

        long positives = 0;
        long retrieved = 0;
        ConfusionMatrix sameCanonical = new ConfusionMatrix();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Loaded a = all.get(i);
                Loaded b = all.get(j);
                boolean truth = a.truthKey().equals(b.truthKey());
                long lo = Math.min(a.listingId(), b.listingId());
                long hi = Math.max(a.listingId(), b.listingId());
                if (truth) {
                    positives++;
                    if (candidatePairs.contains(lo + ":" + hi)) {
                        retrieved++;
                    }
                }
                sameCanonical.record(canonicalOf.get(a.listingId()).equals(canonicalOf.get(b.listingId())), truth);
            }
        }
        double retrievalRecall = (double) retrieved / positives;
        System.out.printf(java.util.Locale.ROOT,
                "DB-MATCHING listings=%d candidatePairs=%d positives=%d sqlRetrievalRecall=%.4f canonicalClusters: %s%n",
                all.size(), candidatePairs.size(), positives, retrievalRecall, sameCanonical);

        assertThat(retrievalRecall).as("SQL candidate retrieval recall").isEqualTo(1.0);
        assertThat(sameCanonical.precision()).as("auto-linked clusters must be precise").isGreaterThanOrEqualTo(0.99);
        assertThat(ctx.count("listing_product_links")).isEqualTo(all.size());
    }

    @Test
    void rematchingIsIdempotent() {
        long candidates = ctx.count("match_candidates");
        long products = ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM canonical_products WHERE status = 'ACTIVE'", Long.class);
        assertThat(ctx.matchingService.matchPending(null, 100, 10_000)).as("nothing stale").isZero();
        loaded.values().forEach(l -> ctx.matchingService.matchListing(l.listingId()));
        assertThat(ctx.matchingService.mergeDuplicateIdentities(1_000)).isZero();
        assertThat(ctx.count("match_candidates")).isEqualTo(candidates);
        assertThat(ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM canonical_products WHERE status = 'ACTIVE'", Long.class)).isEqualTo(products);
    }

    @Test
    void reviewQueueAndProductQueriesExposeEvidence() {
        var page = ctx.matchQueryRepository.searchCandidates("PENDING", null, null, null, null, new PageRequest(null, 5));
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).isSortedAccordingTo((x, y) -> y.score().compareTo(x.score()));
        var first = page.items().get(0);
        assertThat(first.evidenceJson()).contains("\"code\"");
        assertThat(first.left().title()).isNotBlank();
        if (page.nextCursor() != null) {
            var next = ctx.matchQueryRepository.searchCandidates("PENDING", null, null, null, null,
                    new PageRequest(page.nextCursor(), 5));
            assertThat(next.items()).noneMatch(c -> page.items().stream().anyMatch(p -> p.candidateId() == c.candidateId()));
        }
        long sonyListing = loaded.get("sony-wh1000x-01").listingId();
        long productId = ctx.canonicalProductRepository.findLink(sonyListing).orElseThrow().canonicalProductId();
        var linked = ctx.matchQueryRepository.linkedListings(productId);
        assertThat(linked).extracting(MatchQueryRepository.LinkedListingView::listingId).contains(sonyListing);
        assertThat(ctx.matchQueryRepository.getProduct(productId).activeListings()).isEqualTo(linked.size());
    }

    @Test
    void withdrawingAListingSupersedesItsPendingReviews() {
        Loaded target = loaded.values().stream()
                .filter(l -> ctx.jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM match_candidates
                        WHERE (left_listing_id = ? OR right_listing_id = ?) AND review_status = 'PENDING'
                        """, Long.class, l.listingId(), l.listingId()) > 0)
                .findFirst().orElseThrow();
        String externalId = loaded.entrySet().stream().filter(e -> e.getValue() == target).findFirst().orElseThrow().getKey();
        ctx.ingestionService.ingest(sync(withdraw(GoldenCatalogLoader.SOURCE_CODES.get(target.source()), externalId, 2)));
        var outcome = ctx.matchingService.matchListing(target.listingId());
        assertThat(outcome.skippedReason()).contains("withdrawn");
        assertThat(ctx.jdbcTemplate.queryForObject("""
                SELECT count(*) FROM match_candidates
                WHERE (left_listing_id = ? OR right_listing_id = ?) AND review_status = 'PENDING'
                """, Long.class, target.listingId(), target.listingId())).isZero();
    }
}
