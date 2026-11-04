package io.catalogsentinel.domain.normalize;

import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationTest {

    /** Shared fixture also consumed by the PySpark parity tests. */
    @TestFactory
    List<DynamicTest> sharedFixtureCases() {
        Map<String, UnaryOperator<String>> normalizers = Map.of(
                "title", TextNormalizer::normalizeTitle,
                "brand", BrandNormalizer::normalize,
                "model", ModelNormalizer::normalize,
                "category", CategoryNormalizer::normalize);
        return RepoFiles.readTsv("tests/fixtures/normalization-cases.tsv").stream()
                .map(row -> DynamicTest.dynamicTest(row[0] + ": " + row[1], () -> {
                    String expected = row[2].equals("<null>") ? null : row[2];
                    assertThat(normalizers.get(row[0]).apply(row[1])).isEqualTo(expected);
                }))
                .toList();
    }

    @Test
    void inchWordDoesNotSwallowTwoInOne() {
        assertThat(TextNormalizer.normalizeTitle("HP 2 in 1 14 inch laptop")).isEqualTo("hp 2in1 14in laptop");
    }

    @Test
    void modelDigitGroupsDistinguishGenerations() {
        assertThat(ModelNormalizer.digitGroups("wh1000xm5")).containsExactly("1000", "5");
        assertThat(ModelNormalizer.digitGroups("wh1000xm4")).containsExactly("1000", "4");
        assertThat(ModelNormalizer.digitGroups("a0012")).containsExactly("12");
    }

    @Test
    void variantSuffixRecognizesRegionalCodesButNotTiers() {
        assertThat(ModelNormalizer.isVariantSuffix("mq8n3", "mq8n3lla")).isTrue();
        assertThat(ModelNormalizer.isVariantSuffix("smg991b", "smg991bds")).isTrue();
        assertThat(ModelNormalizer.isVariantSuffix("iphone15", "iphone15pro")).isFalse();
        assertThat(ModelNormalizer.isVariantSuffix("iphone15", "iphone15max")).isFalse();
        assertThat(ModelNormalizer.isVariantSuffix("wh1000xm5", "wh1000xm52")).isFalse();
    }

    @Test
    void extractsVariantAttributesFromTitle() {
        String title = TextNormalizer.normalizeTitle("Apple iPad Air 11-inch (M2) 256GB Wi-Fi - Space Grey (2nd Gen)");
        ProductAttributes a = AttributeExtractor.extract(title, Map.of());
        assertThat(a.storageGb()).isEqualTo(256);
        assertThat(a.screenInches()).isEqualTo(11.0);
        assertThat(a.color()).isEqualTo("space gray");
        assertThat(a.generation()).isEqualTo(2);
    }

    @Test
    void ramIsNotMistakenForStorage() {
        String title = TextNormalizer.normalizeTitle("Dell XPS 15 Laptop 32GB RAM 1TB SSD");
        ProductAttributes a = AttributeExtractor.extract(title, Map.of());
        assertThat(a.ramGb()).isEqualTo(32);
        assertThat(a.storageGb()).isEqualTo(1000);
    }

    @Test
    void structuredAttributesWinOverTitle() {
        String title = TextNormalizer.normalizeTitle("Acme Phone 128GB Black");
        ProductAttributes a = AttributeExtractor.extract(title, Map.of("Storage Capacity", "256 GB", "Colour", "Midnight Blue"));
        assertThat(a.storageGb()).isEqualTo(256);
        assertThat(a.color()).isEqualTo("midnight blue");
    }

    @Test
    void packCountsAndVolumesAreCanonical() {
        ProductAttributes a = AttributeExtractor.extract(
                TextNormalizer.normalizeTitle("Sparkling Water 12 fl oz Cans, Pack of 12"), Map.of());
        assertThat(a.packCount()).isEqualTo(12);
        assertThat(a.volumeMl()).isEqualTo(355);
        ProductAttributes b = AttributeExtractor.extract(TextNormalizer.normalizeTitle("Olive Oil 1.5 Litre"), Map.of());
        assertThat(b.volumeMl()).isEqualTo(1500);
    }

    @Test
    void colorFamiliesGroupMarketingNames() {
        assertThat(ColorVocabulary.family("space gray")).isEqualTo("gray");
        assertThat(ColorVocabulary.family("charcoal")).isEqualTo("gray");
        assertThat(ColorVocabulary.family("navy blue")).isEqualTo("blue");
        assertThat(ColorVocabulary.family("jet black")).isEqualTo("black");
        assertThat(ColorVocabulary.canonical("Space Grey")).isEqualTo("space gray");
    }

    @Test
    void productNormalizerProducesCategoryLeaf() {
        NormalizedListing n = ProductNormalizer.normalize("Sony WH-1000XM5", "Sony Corp.", "WH-1000XM5",
                "Electronics > Headphones", Map.of());
        assertThat(n.brand()).isEqualTo("sony");
        assertThat(n.model()).isEqualTo("wh1000xm5");
        assertThat(n.categoryLeaf()).isEqualTo("headphones");
    }
}
