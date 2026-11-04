package io.catalogsentinel.core.metrics;

import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.core.testsupport.GoldenCatalogLoader;
import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The planogram's numbers: per-category parts always add up and agree with the gap and link tables. */
class CoverageIT {

    private final CoreTestContext ctx = new CoreTestContext();
    private long target;

    @BeforeEach
    void setUp() {
        ctx.reset();
        GoldenCatalogLoader.load(ctx, RepoFiles.locate("tests/golden-matching/golden-listings.tsv"));
        target = ctx.sourceService.getByCode("acme-market").sourceId();
    }

    @Test
    void beforeReconciliationUncarriedProductsAreNotEvaluated() {
        ctx.matchingService.matchPending(null, 500, Integer.MAX_VALUE);
        List<DashboardRepository.CoverageRow> rows = ctx.dashboardRepository.coverage(target);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.openGaps() + r.confirmedGaps() + r.dismissedGaps()).isZero();
            assertThat(r.carried() + r.notEvaluated()).isEqualTo(r.referenceProducts());
        });
    }

    @Test
    void afterReconciliationPartsAddUpAndMatchTheGapTable() {
        ReconciliationJob job = ctx.reconciliationService.request("acme-market", null, "test", null);
        ctx.reconciliationService.run(job.jobId());
        List<DashboardRepository.CoverageRow> rows = ctx.dashboardRepository.coverage(target);

        long reference = rows.stream().mapToLong(DashboardRepository.CoverageRow::referenceProducts).sum();
        long open = rows.stream().mapToLong(DashboardRepository.CoverageRow::openGaps).sum();
        long notEvaluated = rows.stream().mapToLong(DashboardRepository.CoverageRow::notEvaluated).sum();
        Long openInTable = ctx.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM selection_gaps WHERE target_source_id = ? AND status = 'OPEN'", Long.class, target);
        Long referenceProducts = ctx.jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT k.canonical_product_id) FROM listing_product_links k
                JOIN product_listings l ON l.listing_id = k.listing_id AND l.status = 'ACTIVE'
                JOIN catalog_sources s ON s.source_id = l.source_id AND s.role = 'REFERENCE'
                JOIN canonical_products c ON c.canonical_product_id = k.canonical_product_id AND c.status = 'ACTIVE'
                """, Long.class);

        assertThat(reference).isEqualTo(referenceProducts);
        assertThat(open).isEqualTo(openInTable).isPositive();
        assertThat(notEvaluated).isZero();
        assertThat(rows).allSatisfy(r -> assertThat(r.carried() + r.openGaps() + r.confirmedGaps()
                + r.dismissedGaps() + r.notEvaluated()).isEqualTo(r.referenceProducts()));
        assertThat(rows).extracting(DashboardRepository.CoverageRow::category).doesNotHaveDuplicates()
                .contains("electronics");
        // Each planogram row links to the gap list filtered by the same category root.
        for (DashboardRepository.CoverageRow r : rows) {
            int listed = ctx.gapQueryRepository.search(target, "OPEN", null, null, null, r.category(), null,
                    new io.catalogsentinel.core.common.PageRequest(null, 200)).items().size();
            assertThat(listed).as(r.category()).isEqualTo((int) r.openGaps());
        }

        // Dismissing a gap moves it between parts without changing the total.
        Long gapId = ctx.jdbcTemplate.queryForObject(
                "SELECT min(gap_id) FROM selection_gaps WHERE status = 'OPEN' AND target_source_id = ?", Long.class, target);
        ctx.reviewService.decideGap(gapId, "DISMISS", "tester", "not relevant", null);
        List<DashboardRepository.CoverageRow> after = ctx.dashboardRepository.coverage(target);
        assertThat(after.stream().mapToLong(DashboardRepository.CoverageRow::dismissedGaps).sum()).isEqualTo(1);
        assertThat(after.stream().mapToLong(DashboardRepository.CoverageRow::referenceProducts).sum()).isEqualTo(reference);
    }
}
