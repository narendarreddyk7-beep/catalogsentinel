package io.catalogsentinel.domain.matching;

import io.catalogsentinel.domain.evaluation.LabeledListing;
import io.catalogsentinel.domain.evaluation.LabeledListingsTsv;
import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedMatcherTest {

    private final RuleBasedMatcher matcher = new RuleBasedMatcher();

    private static ProductFeatures p(String id, String title, String brand, String model, String gtin,
                                     Map<String, String> attributes) {
        return ProductFeatures.fromRaw(id, title, brand, model, "Electronics > Audio", gtin, attributes);
    }

    private MatchResult compare(ProductFeatures a, ProductFeatures b) {
        return matcher.compare(a, b);
    }

    @Test
    void identicalGtinAutoMatches() {
        MatchResult r = compare(p("a", "Sony WH-1000XM5 Headphones", "Sony", null, "4548736132610", Map.of()),
                p("b", "Noise Cancelling Headphones by Sony", null, null, "04548736132610", Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.AUTO_MATCH);
        assertThat(r.score()).isGreaterThanOrEqualTo(0.97);
        assertThat(r.hasEvidence("GTIN_MATCH")).isTrue();
    }

    @Test
    void identicalGtinWithVariantConflictIsADataQualityReview() {
        MatchResult r = compare(p("a", "Acme Phone 128GB Black", "Acme", null, "4548736132610", Map.of()),
                p("b", "Acme Phone 256GB Black", "Acme", null, "4548736132610", Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.REVIEW);
        assertThat(r.hasEvidence("GTIN_MATCH_WITH_CONFLICTS")).isTrue();
    }

    @Test
    void previousGenerationModelIsNotAMatch() {
        MatchResult r = compare(p("a", "Sony WH-1000XM5 Wireless Headphones Black", "Sony", "WH-1000XM5", null, Map.of()),
                p("b", "Sony WH-1000XM4 Wireless Headphones Black", "Sony", "WH-1000XM4", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(r.hasEvidence("MODEL_CONFLICT")).isTrue();
    }

    @Test
    void markIiIsADifferentGenerationThanTheBaseModel() {
        MatchResult r = compare(p("a", "Canon EOS R6 Mark II Body", "Canon", "EOS R6 Mark II", null, Map.of()),
                p("b", "Canon EOS R6 Body", "Canon", "EOS R6", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
    }

    @Test
    void storageVariantsAreNotTheSameProduct() {
        MatchResult r = compare(p("a", "Apple iPhone 15 Pro 128GB Natural Titanium", "Apple", null, null, Map.of()),
                p("b", "Apple iPhone 15 Pro 256GB Natural Titanium", "Apple", null, null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(r.evidence()).anySatisfy(e -> assertThat(e.detail()).contains("storage_gb 128 vs 256"));
    }

    @Test
    void tierWordWithoutAgreeingIdentifiersForcesNoMatch() {
        MatchResult r = compare(p("a", "Apple iPhone 15 128GB Black", "Apple", null, null, Map.of()),
                p("b", "Apple iPhone 15 Pro 128GB Black", "Apple", null, null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(r.hasEvidence("TIER_MISMATCH")).isTrue();
    }

    @Test
    void modelFollowedByTierWordInTitleIsADifferentModel() {
        MatchResult r = compare(p("a", "Keurig K-Mini Coffee Maker, Black", "Keurig", "K-Mini", null, Map.of()),
                p("b", "Keurig K-Mini Plus Coffee Maker, Black", "Keurig", null, null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
    }

    @Test
    void marketingTierWordsAreIgnoredWhenModelsAgree() {
        MatchResult r = compare(p("a", "Samsung 55 inch Q80C QLED 4K Smart TV", "Samsung", "QN55Q80CAFXZA", null, Map.of()),
                p("b", "SAMSUNG 55-Inch Class QLED 4K Q80C Quantum HDR+ Smart TV", "Samsung Electronics", "QN55Q80C", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.AUTO_MATCH);
        assertThat(r.hasEvidence("TIER_WORDS_DIFFER")).isTrue();
    }

    @Test
    void brandMismatchForcesNoMatch() {
        MatchResult r = compare(p("a", "Wireless Mouse M720", "Logitech", "M720", null, Map.of()),
                p("b", "Wireless Mouse M720", "Microsoft", "M720", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(r.hasEvidence("BRAND_MISMATCH")).isTrue();
    }

    @Test
    void brandOwnerAndSubBrandAreToleratedWhenTheTitleNamesTheBrand() {
        MatchResult r = compare(p("a", "Tide PODS Laundry Detergent Pacs, Original Scent, 42 Count", "Tide", null, null, Map.of()),
                p("b", "Tide Pods Original 42 ct Laundry Detergent", "Procter & Gamble", null, null, Map.of()));
        assertThat(r.decision()).isNotEqualTo(MatchDecision.NO_MATCH);
        assertThat(r.hasEvidence("BRAND_OWNER_VARIANT")).isTrue();
    }

    @Test
    void titleSimilarityAloneNeverAutoMatches() {
        MatchResult r = compare(p("a", "Starbucks Pike Place Medium Roast Ground Coffee 12 oz", "Starbucks", null, null, Map.of()),
                p("b", "Starbucks Pike Place Roast Ground Coffee Medium Roast 12oz Bag", "Starbucks", null, null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.REVIEW);
    }

    @Test
    void agreeingModelAndBrandAutoMatchDespiteRewordedTitles() {
        MatchResult r = compare(p("a", "DEWALT 20V MAX Cordless Drill/Driver Kit, Compact, 1/2-Inch (DCD771C2)", "DEWALT", "DCD771C2", null, Map.of()),
                p("b", "Dewalt DCD771C2 20V Max Compact Drill Driver Kit with 2 Batteries", "DeWalt", "DCD771C2", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.AUTO_MATCH);
        assertThat(r.hasEvidence("IDENTIFIER_AGREEMENT")).isTrue();
    }

    @Test
    void unspecifiedVariantBlocksAutoMatch() {
        MatchResult r = compare(p("a", "Acme Speaker X200 Blue", "Acme", "X200", null, Map.of("color", "Blue")),
                p("b", "Acme X200 Portable Speaker", "Acme", "X200", null, Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.REVIEW);
        assertThat(r.hasEvidence("VARIANT_UNSPECIFIED")).isTrue();
    }

    @Test
    void decisionsAreSymmetricAcrossTheWholeGoldenSet() {
        List<LabeledListing> listings = LabeledListingsTsv.read(RepoFiles.locate("tests/golden-matching/golden-listings.tsv"));
        for (int i = 0; i < listings.size(); i++) {
            for (int j = i + 1; j < listings.size(); j++) {
                ProductFeatures a = listings.get(i).features();
                ProductFeatures b = listings.get(j).features();
                assertThat(matcher.compare(b, a).decision())
                        .as("%s vs %s", a.id(), b.id())
                        .isEqualTo(matcher.compare(a, b).decision());
            }
        }
    }

    @Test
    void featureScoresAreBoundedAndEvidenceIsOrderedDeterministically() {
        ProductFeatures a = p("a", "Sony WH-1000XM5 Headphones Black", "Sony", "WH-1000XM5", null, Map.of());
        ProductFeatures b = p("b", "SONY WH1000XM5/B Headphones - Black", null, "WH1000XM5/B", null, Map.of());
        MatchResult first = matcher.compare(a, b);
        MatchResult second = matcher.compare(a, b);
        assertThat(first).isEqualTo(second);
        first.features().asMap().values().stream().filter(v -> v != null)
                .forEach(v -> assertThat(v).isBetween(0.0, 1.0));
    }
}
