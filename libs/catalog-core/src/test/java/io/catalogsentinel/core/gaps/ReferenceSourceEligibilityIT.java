package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which reference listings count for gap detection. Policy (docs/runbook.md, "Reference sources"): only ACTIVE
 * listings of ENABLED reference sources are evidence. Coverage numerator and denominator, demand and the
 * product's eligibility all use that same rule; when a run selects one reference source, all of them are
 * restricted to it. A gap whose product no longer has eligible reference listings is resolved
 * ({@code REFERENCE_SOURCE_DISABLED} when only disabled sources still list it) and a later run reopens it as a
 * new gap once the source is enabled again.
 */
class ReferenceSourceEligibilityIT {

    private static final String REF_A = "ref-alpha";
    private static final String REF_B = "ref-beta";
    private static final String MKT = "mkt-market";

    private final CoreTestContext ctx = new CoreTestContext();
    private long refB;
    private long lanternProduct;
    private long kettleProduct;
    private long bottleProduct;

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(REF_A, "Reference Alpha", SourceRole.REFERENCE, true);
        refB = ctx.sourceService.register(REF_B, "Reference Beta", SourceRole.REFERENCE, true).sourceId();
        ctx.sourceService.register(MKT, "Market", SourceRole.MARKETPLACE, true);
        // Lantern: only alpha lists it. Kettle: both list it (alpha demand 0.2, beta 0.8). Bottle: only beta.
        long lantern = ingest(REF_A, "A-1", "Kestrel Trail KT-200 LED Camping Lantern, Green", "Kestrel", "KT-200",
                "2000000000015", "0.9000");
        long kettleA = ingest(REF_A, "A-2", "Vantor Brew VB-17 1.7 L Electric Kettle, Steel", "Vantor", "VB-17",
                "2000000000022", "0.2000");
        ingest(REF_B, "B-2", "Vantor Brew VB-17 Electric Kettle 1.7L Stainless", "Vantor", "VB-17", "2000000000022",
                "0.8000");
        long bottle = ingest(REF_B, "B-3", "Brightfield Nova BR-3265E 24 oz Insulated Water Bottle, Navy",
                "Brightfield", "BR-3265E", "2000000000039", "0.5000");
        // The marketplace carries something unrelated, so every reference product is a gap.
        ingest(MKT, "M-1", "Altora Pro AL-4520X 64GB USB Flash Drive, Black", "Altora", "AL-4520X", "4006381333931", null);
        ctx.matchingService.matchPending(null, 100, 1_000);
        ctx.matchingService.mergeDuplicateIdentities(100);
        lanternProduct = productOf(lantern);
        kettleProduct = productOf(kettleA);
        bottleProduct = productOf(bottle);
        assertThat(List.of(lanternProduct, kettleProduct, bottleProduct)).doesNotHaveDuplicates();
    }

    private long ingest(String source, String externalId, String title, String brand, String model, String gtin,
                        String demand) {
        Map<String, String> attributes = demand == null ? Map.of() : Map.of("synthetic_demand_index", demand);
        return ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), source, externalId, 1, title, brand, model,
                gtin, attributes))).listingId();
    }

    private long productOf(long listingId) {
        return ctx.jdbcTemplate.queryForObject(
                "SELECT canonical_product_id FROM listing_product_links WHERE listing_id = ?", Long.class, listingId);
    }

    private ReconciliationJob reconcile(String referenceCode) {
        ReconciliationJob job = ctx.reconciliationService.request(MKT, referenceCode, "test", null);
        ReconciliationJob done = ctx.reconciliationService.run(job.jobId());
        assertThat(done.status()).as(String.valueOf(done.errorMessage())).isEqualTo("SUCCEEDED");
        return done;
    }

    private Optional<GapView> liveGap(long productId) {
        return ctx.gapQueryRepository.search(null, null, null, null, null, null, new PageRequest(null, 200)).items()
                .stream().filter(g -> g.canonicalProductId() == productId && !g.status().equals("RESOLVED")).findFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> evidence(GapView gap) {
        return ctx.json.read(gap.evidenceJson(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Number> coverage(Map<String, Object> evidence) {
        return (Map<String, Number>) evidence.get("referenceCoverage");
    }

    @SuppressWarnings("unchecked")
    private static double demand(Map<String, Object> evidence) {
        return ((Map<String, Number>) evidence.get("demand")).get("value").doubleValue();
    }

    @Test
    void allEnabledSourcesCountForCoverageAndDemand() {
        reconcile(null);
        Map<String, Object> kettle = evidence(liveGap(kettleProduct).orElseThrow());
        assertThat(coverage(kettle)).containsEntry("sources", 2).containsEntry("of", 2);
        assertThat(demand(kettle)).isEqualTo(0.8);
        assertThat(coverage(evidence(liveGap(lanternProduct).orElseThrow()))).containsEntry("sources", 1)
                .containsEntry("of", 2);
    }

    @Test
    void disablingASourceRemovesItsExclusiveGapsAndItsEvidenceUntilItIsEnabledAgain() {
        reconcile(null);
        long bottleGap = liveGap(bottleProduct).orElseThrow().gapId();

        ctx.sourceService.setEnabled(refB, false);
        reconcile(null);

        assertThat(liveGap(bottleProduct)).as("a product only a disabled source lists is not an active gap").isEmpty();
        GapView resolved = ctx.gapQueryRepository.get(bottleGap);
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolution()).isEqualTo("REFERENCE_SOURCE_DISABLED");
        Map<String, Object> kettle = evidence(liveGap(kettleProduct).orElseThrow());
        assertThat(coverage(kettle)).as("numerator and denominator use the same eligibility")
                .containsEntry("sources", 1).containsEntry("of", 1);
        assertThat(demand(kettle)).as("demand only from eligible listings").isEqualTo(0.2);
        assertThat(ctx.dashboardRepository.coverage(ctx.sourceService.getByCode(MKT).sourceId()))
                .as("dashboard coverage agrees with reconciliation")
                .extracting(r -> r.referenceProducts()).containsExactly(2L);

        ctx.sourceService.setEnabled(refB, true);
        reconcile(null);
        GapView reopened = liveGap(bottleProduct).orElseThrow();
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.gapId()).as("history is kept; the gap reappears as a new row").isNotEqualTo(bottleGap);
        assertThat(coverage(evidence(liveGap(kettleProduct).orElseThrow()))).containsEntry("sources", 2)
                .containsEntry("of", 2);
    }

    @Test
    void aSelectedReferenceSourceScopesEligibilityCoverageAndDemand() {
        reconcile(null);
        long lanternGap = liveGap(lanternProduct).orElseThrow().gapId();

        reconcile(REF_B);

        Map<String, Object> kettle = evidence(liveGap(kettleProduct).orElseThrow());
        assertThat(coverage(kettle)).containsEntry("sources", 1).containsEntry("of", 1);
        assertThat(demand(kettle)).isEqualTo(0.8);
        assertThat(liveGap(lanternProduct)).as("a scoped run leaves products outside its scope alone")
                .hasValueSatisfying(g -> assertThat(g.gapId()).isEqualTo(lanternGap));
    }

    @Test
    void aDisabledReferenceSourceCannotBeSelected() {
        ctx.sourceService.setEnabled(refB, false);
        assertThatThrownBy(() -> ctx.reconciliationService.request(MKT, REF_B, "test", null))
                .isInstanceOf(UnprocessableException.class)
                .satisfies(e -> assertThat(((UnprocessableException) e).code()).isEqualTo("REFERENCE_SOURCE_DISABLED"));
    }

    @Test
    void aRunWhoseSelectedSourceWasDisabledMeanwhileFailsVisiblyWithoutTouchingGaps() {
        reconcile(null);
        long gapsBefore = ctx.count("selection_gaps");
        ReconciliationJob job = ctx.reconciliationService.request(MKT, REF_B, "test", null);
        ctx.sourceService.setEnabled(refB, false);

        ReconciliationJob done = ctx.reconciliationService.run(job.jobId());

        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.errorMessage()).contains("REFERENCE_SOURCE_DISABLED");
        assertThat(ctx.count("selection_gaps")).isEqualTo(gapsBefore);
    }
}
