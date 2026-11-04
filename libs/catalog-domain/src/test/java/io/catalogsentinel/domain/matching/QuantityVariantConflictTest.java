package io.catalogsentinel.domain.matching;

import io.catalogsentinel.domain.normalize.AttributeExtractor;
import io.catalogsentinel.domain.normalize.ProductAttributes;
import io.catalogsentinel.domain.normalize.TextNormalizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * rules-v4: voltage and plain-ounce quantities are variant-defining. Found by error analysis on the synthetic
 * catalog (docs/benchmarks/matching/rules-v3-synthetic-seed7.md): same brand and model, different 12V/20V or
 * 18 oz/24 oz variants were auto-matched by rules-v3 because neither quantity was extracted.
 */
class QuantityVariantConflictTest {

    private final RuleBasedMatcher matcher = new RuleBasedMatcher();

    private static ProductFeatures listing(String id, String title, Map<String, String> attributes) {
        return ProductFeatures.fromRaw(id, title, "Altora", "AL-6811X", "Tools > Power Tools > Drills", null, attributes);
    }

    @Test
    void extractsVoltageAndOuncesFromTitlesAndStructuredAttributes() {
        ProductAttributes a = AttributeExtractor.extract(TextNormalizer.normalizeTitle("Altora Nova 20 Volt Impact Driver"), Map.of());
        ProductAttributes b = AttributeExtractor.extract(TextNormalizer.normalizeTitle("Bottle 18 OZ, Navy"), Map.of());
        ProductAttributes c = AttributeExtractor.extract(TextNormalizer.normalizeTitle("Bottle"),
                Map.of("capacity", "24 oz", "voltage", "12 V"));
        ProductAttributes fluid = AttributeExtractor.extract(TextNormalizer.normalizeTitle("Bottle 24 fl oz"), Map.of());

        assertThat(a.voltage()).isEqualTo(20.0);
        assertThat(b.ounces()).isEqualTo(18.0);
        assertThat(c.ounces()).isEqualTo(24.0);
        assertThat(c.voltage()).isEqualTo(12.0);
        // Fluid ounces are converted to millilitres and never compared with plain ounces.
        assertThat(fluid.ounces()).isNull();
        assertThat(fluid.volumeMl()).isEqualTo(710);
        assertThat(a.asMap()).containsEntry("voltage_v", "20");
    }

    @Test
    void differentVoltagesOrOuncesAreAVariantConflict() {
        MatchResult volts = matcher.compare(listing("a", "Altora Nova AL-6811X 20V Impact Driver, Red", Map.of()),
                listing("b", "Altora Nova AL-6811X 12V Impact Driver, Red", Map.of("voltage", "12 V")));
        MatchResult ounces = matcher.compare(listing("c", "Altora Nova AL-6811X 18 OZ Water Bottle, Navy", Map.of()),
                listing("d", "Altora Nova AL-6811X 24 oz Water Bottle, Navy", Map.of()));

        assertThat(volts.decision()).isEqualTo(MatchDecision.NO_MATCH);
        assertThat(volts.evidence()).anySatisfy(e -> assertThat(e.code() + " " + e.detail())
                .isEqualTo("VARIANT_CONFLICT voltage_v 20 vs 12"));
        assertThat(ounces.decision()).isEqualTo(MatchDecision.NO_MATCH);
    }

    @Test
    void equalQuantitiesWithinToleranceStillMatch() {
        MatchResult r = matcher.compare(listing("a", "Altora Nova AL-6811X 20V Impact Driver, Red", Map.of()),
                listing("b", "ALTORA NOVA AL6811X 20 Volt Impact Driver Red", Map.of()));
        assertThat(r.decision()).isEqualTo(MatchDecision.AUTO_MATCH);
    }
}
