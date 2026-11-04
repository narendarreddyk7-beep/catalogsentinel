package io.catalogsentinel.domain.matching;

import io.catalogsentinel.domain.normalize.ModelNormalizer;
import io.catalogsentinel.domain.normalize.NormalizedListing;
import io.catalogsentinel.domain.normalize.ProductAttributes;
import io.catalogsentinel.domain.normalize.ProductNormalizer;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Precomputed matching features of one listing. Built once per listing and reused across all candidate
 * comparisons, so pairwise scoring does no string normalization.
 *
 * @param id             caller-defined identifier (listing id)
 * @param rawTitle       title as supplied (used only by the title-only baseline)
 * @param titleTokens    normalized title words minus stopwords
 * @param titleTrigrams  pg_trgm-compatible trigram set of the normalized title
 * @param modelLikeTokens title tokens that look like part numbers (mixed letters and digits, length >= 4)
 * @param bareNumbers    standalone numbers in the title that are not years (e.g. "40" in "Pegasus 40")
 */
public record ProductFeatures(
        String id,
        String rawTitle,
        String gtin,
        String brand,
        String model,
        List<String> modelDigits,
        String normalizedTitle,
        Set<String> titleTokens,
        Set<String> titleTrigrams,
        Set<String> modelLikeTokens,
        Set<String> bareNumbers,
        String category,
        String categoryLeaf,
        ProductAttributes attributes) {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "for", "with", "of", "in", "by", "to", "on", "new", "brand", "genuine",
            "original", "authentic", "latest", "version", "edition", "model", "pack", "x", "w", "s");
    private static final Pattern MIXED_ALNUM = Pattern.compile("(?=.*[a-z])(?=.*[0-9])[a-z0-9]{4,}");
    private static final Pattern UNIT_TOKEN =
            Pattern.compile("\\d+(?:\\.\\d+)?(gb|tb|mb|mah|w|v|hz|in|ml|l|floz|oz|qt|gal|lb|kg|mm|cm|pk)");
    private static final Pattern BARE_NUMBER = Pattern.compile("\\d{1,4}");

    public ProductFeatures {
        titleTokens = Collections.unmodifiableSet(titleTokens);
        titleTrigrams = Collections.unmodifiableSet(titleTrigrams);
        modelLikeTokens = Collections.unmodifiableSet(modelLikeTokens);
        bareNumbers = Collections.unmodifiableSet(bareNumbers);
        modelDigits = List.copyOf(modelDigits);
    }

    public static ProductFeatures of(String id, String rawTitle, String gtin, NormalizedListing n) {
        Set<String> tokens = new LinkedHashSet<>();
        Set<String> modelLike = new LinkedHashSet<>();
        Set<String> numbers = new LinkedHashSet<>();
        String title = n.title() == null ? "" : n.title();
        for (String t : title.split(" ")) {
            if (t.isEmpty()) {
                continue;
            }
            if (!STOPWORDS.contains(t)) {
                tokens.add(t);
            }
            if (MIXED_ALNUM.matcher(t).matches() && !UNIT_TOKEN.matcher(t).matches() && !t.startsWith("gen")) {
                modelLike.add(t);
            }
            if (BARE_NUMBER.matcher(t).matches() && !isYear(t)) {
                numbers.add(t.replaceFirst("^0+(?=\\d)", ""));
            }
        }
        return new ProductFeatures(id, rawTitle, gtin, n.brand(), n.model(), ModelNormalizer.digitGroups(n.model()), title,
                tokens, Similarity.trigrams(title), modelLike, numbers, n.category(), n.categoryLeaf(), n.attributes());
    }

    /**
     * Convenience for tests, evaluation and batch jobs: normalize raw fields (including GTIN canonicalization;
     * invalid GTINs are dropped) and build features.
     */
    public static ProductFeatures fromRaw(String id, String title, String brand, String model, String category,
                                          String gtin, Map<String, String> attributes) {
        String canonicalGtin = gtin == null ? null
                : io.catalogsentinel.domain.listing.Gtin.parse(gtin).map(io.catalogsentinel.domain.listing.Gtin::value).orElse(null);
        return of(id, title, canonicalGtin, ProductNormalizer.normalize(title, brand, model, category, attributes));
    }

    private static boolean isYear(String t) {
        if (t.length() != 4) {
            return false;
        }
        int y = Integer.parseInt(t);
        return y >= 1990 && y <= 2035;
    }
}
