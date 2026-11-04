package io.catalogsentinel.domain.matching;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-feature similarity scores of a pair; {@code null} means the feature could not be compared (missing on
 * at least one side) and is excluded from the weighted score rather than counted as a mismatch.
 */
public record FeatureScores(Double gtin, Double brand, Double model, Double title, Double attributes, Double category) {

    public Map<String, Double> asMap() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("gtin", round(gtin));
        m.put("brand", round(brand));
        m.put("model", round(model));
        m.put("title", round(title));
        m.put("attributes", round(attributes));
        m.put("category", round(category));
        return m;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10_000.0) / 10_000.0;
    }
}
