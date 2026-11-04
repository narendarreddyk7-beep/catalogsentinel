package io.catalogsentinel.domain.matching;

import io.catalogsentinel.domain.matching.MatchEvidence.Effect;
import io.catalogsentinel.domain.normalize.ColorVocabulary;
import io.catalogsentinel.domain.normalize.ModelNormalizer;
import io.catalogsentinel.domain.normalize.ProductAttributes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, explainable product matcher ("optimized" algorithm).
 *
 * <p>Pipeline per pair:
 * <ol>
 *   <li>Feature scores: GTIN, brand, model number, title (token Jaccard + pg_trgm-style trigram similarity),
 *       variant attributes (storage, color family, size, generation, pack count...), category.</li>
 *   <li>A weighted score over the features that are available on both sides (missing features are excluded,
 *       not counted as mismatches).</li>
 *   <li>Explicit rules, in precedence order, that can override the weighted score:
 *     <ul>
 *       <li>Equal GTINs auto-match, unless brand/model/variant attributes conflict (then REVIEW: data quality).</li>
 *       <li>Brand mismatch, model-number digit conflict, tier mismatch (Pro vs non-Pro) and variant attribute
 *           conflicts force NO_MATCH: the products are different, however similar the titles look.</li>
 *       <li>Different GTINs block auto-matching (regional/repackaged GTINs exist, so REVIEW at best).</li>
 *       <li>Auto-matching additionally requires identifier agreement (GTIN or model number). Title similarity
 *           alone can only reach REVIEW.</li>
 *       <li>Unmatched bare numbers on both sides ("Pegasus 40" vs "Pegasus 41") penalize the score.</li>
 *       <li>Identifier-driven auto-match: an agreeing model number plus brand agreement, with no conflicts and
 *           every stated variant attribute present on both sides, auto-matches even when titles are reworded.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>v2 changes (from error analysis on the development set, then evaluated on an untouched holdout set; see
 * docs/benchmarks/matching): brand-vs-manufacturer tolerance, model prefix/series/region-suffix relations,
 * digit conflicts only between part numbers of the same scheme, tier words ignored when identifiers agree,
 * category overlap instead of Jaccard, and the variant-completeness guard on auto-matching.
 *
 * <p>v3 change (motivated by the single v2 auto-match false positive on the holdout set, so v3's holdout
 * numbers are not unbiased): a model number found in a title only counts as agreement when it matches whole
 * title tokens and is not immediately followed by a tier word ("k mini plus" is not model "kmini").
 */
public final class RuleBasedMatcher implements ProductMatcher {

    public static final String VERSION = "rules-v4";

    private static final double W_MODEL = 0.35;
    private static final double W_TITLE = 0.25;
    private static final double W_BRAND = 0.15;
    private static final double W_ATTRIBUTES = 0.15;
    private static final double W_CATEGORY = 0.10;
    private static final double NUMBER_MISMATCH_PENALTY = 0.25;
    private static final Set<String> TIER_WORDS =
            Set.of("pro", "max", "plus", "mini", "ultra", "lite", "air", "se", "fe", "xl");

    private final MatchThresholds thresholds;

    public RuleBasedMatcher() {
        this(MatchThresholds.DEFAULT);
    }

    public RuleBasedMatcher(MatchThresholds thresholds) {
        this.thresholds = thresholds;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public MatchResult compare(ProductFeatures a, ProductFeatures b) {
        List<MatchEvidence> evidence = new ArrayList<>();

        Double gtin = null;
        boolean gtinMatch = false;
        boolean gtinConflict = false;
        if (a.gtin() != null && b.gtin() != null) {
            if (a.gtin().equals(b.gtin())) {
                gtin = 1.0;
                gtinMatch = true;
                evidence.add(MatchEvidence.supports("GTIN_MATCH", a.gtin()));
            } else {
                gtin = 0.0;
                gtinConflict = true;
                evidence.add(new MatchEvidence("GTIN_CONFLICT", Effect.BLOCKS_AUTO_MATCH, a.gtin() + " vs " + b.gtin()));
            }
        }

        Double brand = brandScore(a, b, evidence);
        boolean brandConflict = brand != null && brand == 0.0;

        ModelComparison model = compareModels(a, b, evidence);

        double tokenSim = Similarity.jaccard(a.titleTokens(), b.titleTokens());
        double trigramSim = Similarity.trigramSimilarity(a.titleTrigrams(), b.titleTrigrams());
        double title = 0.5 * tokenSim + 0.5 * trigramSim;
        if (title >= 0.8) {
            evidence.add(MatchEvidence.supports("TITLE_SIMILAR", fmt(title)));
        }

        AttributeComparison attrs = compareAttributes(a.attributes(), b.attributes(), evidence);

        Set<String> tiersA = tiers(a);
        Set<String> tiersB = tiers(b);
        boolean identifiersAgree = gtinMatch || model.agrees;
        boolean tierMismatch = !tiersA.equals(tiersB) && !identifiersAgree;
        if (!tiersA.equals(tiersB)) {
            // With agreeing identifiers, words like "plus" (HDR+) or "max" (20V MAX) are marketing, not tiers.
            evidence.add(tierMismatch
                    ? new MatchEvidence("TIER_MISMATCH", Effect.FORCES_NO_MATCH, tiersA + " vs " + tiersB)
                    : MatchEvidence.contradicts("TIER_WORDS_DIFFER", tiersA + " vs " + tiersB + " (identifiers agree)"));
        }

        boolean numberMismatch = numberMismatch(a, b);
        if (numberMismatch) {
            evidence.add(new MatchEvidence("NUMBER_MISMATCH", Effect.BLOCKS_AUTO_MATCH,
                    onlyIn(a.bareNumbers(), b.bareNumbers()) + " vs " + onlyIn(b.bareNumbers(), a.bareNumbers())));
        }

        Double category = categoryScore(a, b);

        double weightSum = W_TITLE;
        double weighted = W_TITLE * title;
        if (model.score != null) {
            weightSum += W_MODEL;
            weighted += W_MODEL * model.score;
        }
        if (brand != null) {
            weightSum += W_BRAND;
            weighted += W_BRAND * brand;
        }
        if (attrs.score != null) {
            weightSum += W_ATTRIBUTES;
            weighted += W_ATTRIBUTES * attrs.score;
        }
        if (category != null) {
            weightSum += W_CATEGORY;
            weighted += W_CATEGORY * category;
        }
        double score = weighted / weightSum;
        FeatureScores features = new FeatureScores(gtin, brand, model.score, title, attrs.score, category);

        boolean hardConflict = brandConflict || model.conflict || attrs.conflict || tierMismatch;
        if (gtinMatch) {
            if (hardConflict) {
                evidence.add(new MatchEvidence("GTIN_MATCH_WITH_CONFLICTS", Effect.BLOCKS_AUTO_MATCH,
                        "identical GTIN but other identifiers disagree: likely a data-quality issue"));
                return result(Math.min(score, belowAuto()), MatchDecision.REVIEW, features, evidence);
            }
            return result(Math.max(score, 0.97), MatchDecision.AUTO_MATCH, features, evidence);
        }
        if (brandConflict) {
            return result(Math.min(score, 0.25), MatchDecision.NO_MATCH, features, evidence);
        }
        if (model.conflict) {
            return result(Math.min(score, 0.35), MatchDecision.NO_MATCH, features, evidence);
        }
        if (tierMismatch || attrs.conflict) {
            return result(Math.min(score, 0.45), MatchDecision.NO_MATCH, features, evidence);
        }
        if (numberMismatch) {
            score = Math.max(0.0, score - NUMBER_MISMATCH_PENALTY);
        }
        if (gtinConflict) {
            if (model.agrees && score >= thresholds.review()) {
                return result(Math.min(score, 0.85), MatchDecision.REVIEW, features, evidence);
            }
            return result(Math.min(score, 0.40), MatchDecision.NO_MATCH, features, evidence);
        }
        boolean variantsComplete = variantKeys(a.attributes()).equals(variantKeys(b.attributes()));
        boolean brandAgrees = brand != null && brand >= 0.9;
        if (model.agrees && brandAgrees && !numberMismatch && title >= 0.30) {
            if (variantsComplete) {
                evidence.add(MatchEvidence.supports("IDENTIFIER_AGREEMENT", "model and brand agree"));
                return result(Math.max(score, thresholds.autoMatch()), MatchDecision.AUTO_MATCH, features, evidence);
            }
            evidence.add(new MatchEvidence("VARIANT_UNSPECIFIED", Effect.BLOCKS_AUTO_MATCH,
                    variantKeys(a.attributes()) + " vs " + variantKeys(b.attributes())));
            return result(Math.max(Math.min(score, belowAuto()), thresholds.review()), MatchDecision.REVIEW, features, evidence);
        }
        if (score >= thresholds.autoMatch()) {
            if (model.agrees && !numberMismatch && variantsComplete) {
                return result(score, MatchDecision.AUTO_MATCH, features, evidence);
            }
            evidence.add(new MatchEvidence("NO_IDENTIFIER_AGREEMENT", Effect.BLOCKS_AUTO_MATCH,
                    "similar content but no GTIN or model-number agreement"));
            return result(belowAuto(), MatchDecision.REVIEW, features, evidence);
        }
        if (score >= thresholds.review()) {
            return result(score, MatchDecision.REVIEW, features, evidence);
        }
        return result(score, MatchDecision.NO_MATCH, features, evidence);
    }

    private double belowAuto() {
        return thresholds.autoMatch() - 0.0001;
    }

    private MatchResult result(double score, MatchDecision decision, FeatureScores features, List<MatchEvidence> evidence) {
        return new MatchResult(Math.max(0, Math.min(1, score)), decision, features, evidence, VERSION);
    }

    private static Double brandScore(ProductFeatures a, ProductFeatures b, List<MatchEvidence> evidence) {
        if (a.brand() != null && b.brand() != null) {
            if (a.brand().equals(b.brand())) {
                evidence.add(MatchEvidence.supports("BRAND_MATCH", a.brand()));
                return 1.0;
            }
            double jw = Similarity.jaroWinkler(a.brand(), b.brand());
            if (jw >= 0.92) {
                evidence.add(MatchEvidence.supports("BRAND_SIMILAR", a.brand() + " ~ " + b.brand()));
                return jw;
            }
            Set<String> wa = new HashSet<>(List.of(a.brand().split(" ")));
            Set<String> wb = new HashSet<>(List.of(b.brand().split(" ")));
            if (wa.containsAll(wb) || wb.containsAll(wa)) {
                // "purina" vs "purina pro plan", "sony" vs "sony interactive entertainment"
                evidence.add(MatchEvidence.supports("BRAND_SUBSUMED", a.brand() + " ~ " + b.brand()));
                return 0.9;
            }
            if (titleMentions(b, a.brand()) || titleMentions(a, b.brand())) {
                // Brand vs manufacturer/parent: "tide" vs "procter gamble" when the title says "Tide ...".
                evidence.add(MatchEvidence.supports("BRAND_OWNER_VARIANT", a.brand() + " ~ " + b.brand()));
                return 0.8;
            }
            evidence.add(new MatchEvidence("BRAND_MISMATCH", Effect.FORCES_NO_MATCH, a.brand() + " vs " + b.brand()));
            return 0.0;
        }
        ProductFeatures withBrand = a.brand() != null ? a : b.brand() != null ? b : null;
        if (withBrand == null) {
            return null;
        }
        ProductFeatures other = withBrand == a ? b : a;
        if (titleMentions(other, withBrand.brand())) {
            evidence.add(MatchEvidence.supports("BRAND_IN_TITLE", withBrand.brand()));
            return 0.9;
        }
        return null;
    }

    private static boolean titleMentions(ProductFeatures f, String phrase) {
        Set<String> words = new HashSet<>(List.of(f.normalizedTitle().split(" ")));
        if (words.containsAll(List.of(phrase.split(" ")))) {
            return true;
        }
        // "coca cola" brand vs "cocacola" written as one word, or vice versa.
        return f.normalizedTitle().replace(" ", "").contains(phrase.replace(" ", ""));
    }

    private static ModelComparison compareModels(ProductFeatures a, ProductFeatures b, List<MatchEvidence> evidence) {
        if (a.model() != null && b.model() != null) {
            return relateModels(a.model(), a.modelDigits(), b.model(), b.modelDigits(), "", evidence);
        }
        ProductFeatures withModel = a.model() != null ? a : b.model() != null ? b : null;
        if (withModel != null) {
            ProductFeatures other = withModel == a ? b : a;
            String m = withModel.model();
            TitleModelMatch inTitle = findModelInTitle(other.normalizedTitle(), m);
            if (inTitle == TitleModelMatch.TIER_FOLLOWS) {
                evidence.add(new MatchEvidence("MODEL_CONFLICT", Effect.FORCES_NO_MATCH,
                        m + " is followed by a tier word in the other title"));
                return new ModelComparison(0.0, false, true);
            }
            if (inTitle == TitleModelMatch.MATCH) {
                evidence.add(MatchEvidence.supports("MODEL_IN_TITLE", m));
                return new ModelComparison(0.95, true, false);
            }
            for (String token : other.modelLikeTokens()) {
                ModelRelation rel = relation(m, withModel.modelDigits(), token, ModelNormalizer.digitGroups(token));
                if (rel.agrees()) {
                    evidence.add(MatchEvidence.supports("MODEL_IN_TITLE", m + " ~ " + token + " (" + rel + ")"));
                    return new ModelComparison(0.9, true, false);
                }
                if (rel == ModelRelation.CONFLICT) {
                    evidence.add(new MatchEvidence("MODEL_CONFLICT", Effect.FORCES_NO_MATCH, m + " vs " + token + " (title)"));
                    return new ModelComparison(0.0, false, true);
                }
            }
            return new ModelComparison(null, false, false);
        }
        Set<String> shared = new HashSet<>(a.modelLikeTokens());
        shared.retainAll(b.modelLikeTokens());
        if (!shared.isEmpty()) {
            evidence.add(MatchEvidence.supports("MODEL_TOKEN_IN_BOTH_TITLES", String.join(",", new java.util.TreeSet<>(shared))));
            return new ModelComparison(0.9, true, false);
        }
        for (String ta : a.modelLikeTokens()) {
            for (String tb : b.modelLikeTokens()) {
                if (relation(ta, ModelNormalizer.digitGroups(ta), tb, ModelNormalizer.digitGroups(tb)) == ModelRelation.CONFLICT) {
                    evidence.add(new MatchEvidence("MODEL_CONFLICT", Effect.FORCES_NO_MATCH, ta + " vs " + tb + " (titles)"));
                    return new ModelComparison(0.0, false, true);
                }
            }
        }
        return new ModelComparison(null, false, false);
    }

    private enum TitleModelMatch { MATCH, TIER_FOLLOWS, NONE }

    /**
     * Looks for the model as one title token or as a run of consecutive tokens ("k mini" for "kmini"), never as
     * an arbitrary substring. A match followed directly by a tier word denotes a different product tier.
     */
    private static TitleModelMatch findModelInTitle(String normalizedTitle, String model) {
        String[] tokens = normalizedTitle.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            StringBuilder joined = new StringBuilder();
            for (int j = i; j < tokens.length && joined.length() < model.length(); j++) {
                joined.append(tokens[j]);
                boolean multiToken = j > i;
                if (joined.toString().equals(model) && (!multiToken || model.length() >= 5)) {
                    boolean tierFollows = j + 1 < tokens.length && TIER_WORDS.contains(tokens[j + 1]);
                    return tierFollows ? TitleModelMatch.TIER_FOLLOWS : TitleModelMatch.MATCH;
                }
            }
        }
        return TitleModelMatch.NONE;
    }

    private static ModelComparison relateModels(String ma, List<String> da, String mb, List<String> db, String note,
                                                List<MatchEvidence> evidence) {
        ModelRelation rel = relation(ma, da, mb, db);
        String detail = ma + " vs " + mb + note;
        return switch (rel) {
            case EXACT -> {
                evidence.add(MatchEvidence.supports("MODEL_MATCH", ma));
                yield new ModelComparison(1.0, true, false);
            }
            case REGION_SUFFIX, PREFIX, SERIES_PREFIX -> {
                evidence.add(MatchEvidence.supports("MODEL_" + rel.name(), detail));
                yield new ModelComparison(rel == ModelRelation.REGION_SUFFIX ? 0.92 : 0.88, true, false);
            }
            case CONFLICT -> {
                evidence.add(new MatchEvidence("MODEL_CONFLICT", Effect.FORCES_NO_MATCH, detail));
                yield new ModelComparison(0.0, false, true);
            }
            case UNRELATED -> {
                double jw = Similarity.jaroWinkler(ma, mb);
                evidence.add(new MatchEvidence(jw >= 0.9 ? "MODEL_SIMILAR" : "MODEL_DIFFERENT",
                        jw >= 0.9 ? Effect.SUPPORTS : Effect.CONTRADICTS, detail));
                yield new ModelComparison(0.8 * jw, false, false);
            }
        };
    }

    /** How two normalized part numbers relate. */
    enum ModelRelation {
        EXACT, REGION_SUFFIX, PREFIX, SERIES_PREFIX, CONFLICT, UNRELATED;

        boolean agrees() {
            return this == EXACT || this == REGION_SUFFIX || this == PREFIX || this == SERIES_PREFIX;
        }
    }

    static ModelRelation relation(String a, List<String> digitsA, String b, List<String> digitsB) {
        if (a.equals(b)) {
            return ModelRelation.EXACT;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (longer.startsWith(shorter)) {
            String rest = longer.substring(shorter.length());
            if (rest.matches("(?:mk|gen)\\d+")) {
                return ModelRelation.CONFLICT; // "eosr6" vs "eosr6mk2": a later generation
            }
            if (ModelNormalizer.isVariantSuffix(shorter, longer)) {
                return ModelRelation.REGION_SUFFIX; // "mq8n3" vs "mq8n3lla"
            }
            if (shorter.length() >= 6 && !TIER_WORDS.contains(rest)) {
                return ModelRelation.PREFIX; // "sdsqxaa128g" vs "sdsqxaa128ggn6ma"
            }
        }
        if (longer.endsWith(shorter) && shorter.length() >= 4 && shorter.matches(".*\\d.*")
                && longer.substring(0, longer.length() - shorter.length()).matches("[a-z]+")) {
            return ModelRelation.SERIES_PREFIX; // "archerax55" vs "ax55"
        }
        if (!digitsA.isEmpty() && !digitsB.isEmpty() && !digitsA.equals(digitsB)
                && sameScheme(a, b)) {
            return ModelRelation.CONFLICT; // "wh1000xm5" vs "wh1000xm4"
        }
        return ModelRelation.UNRELATED;
    }

    /** Same part-number scheme: identical letters once digits are removed ("whxm" for wh1000xm4/5). */
    private static boolean sameScheme(String a, String b) {
        String la = a.replaceAll("[0-9]", "");
        String lb = b.replaceAll("[0-9]", "");
        return !la.isEmpty() && la.equals(lb);
    }

    private static AttributeComparison compareAttributes(ProductAttributes a, ProductAttributes b,
                                                         List<MatchEvidence> evidence) {
        double total = 0;
        int compared = 0;
        boolean conflict = false;
        Object[][] exact = {
                {"storage_gb", a.storageGb(), b.storageGb()},
                {"ram_gb", a.ramGb(), b.ramGb()},
                {"generation", a.generation(), b.generation()},
                {"pack_count", a.packCount(), b.packCount()},
                {"wattage_w", a.wattage(), b.wattage()},
                {"size", a.size(), b.size()}};
        for (Object[] row : exact) {
            if (row[1] != null && row[2] != null) {
                compared++;
                if (Objects.equals(row[1], row[2])) {
                    total += 1;
                } else {
                    conflict = true;
                    evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH, row[0] + " " + row[1] + " vs " + row[2]));
                }
            }
        }
        if (a.screenInches() != null && b.screenInches() != null) {
            compared++;
            if (Math.abs(a.screenInches() - b.screenInches()) <= 0.15) {
                total += 1;
            } else {
                conflict = true;
                evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH,
                        "screen_in " + a.screenInches() + " vs " + b.screenInches()));
            }
        }
        if (a.volumeMl() != null && b.volumeMl() != null) {
            compared++;
            if (Math.abs(a.volumeMl() - b.volumeMl()) <= 0.03 * Math.max(a.volumeMl(), b.volumeMl())) {
                total += 1;
            } else {
                conflict = true;
                evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH,
                        "volume_ml " + a.volumeMl() + " vs " + b.volumeMl()));
            }
        }
        if (a.capacityMah() != null && b.capacityMah() != null) {
            compared++;
            if (Math.abs(a.capacityMah() - b.capacityMah()) <= 0.02 * Math.max(a.capacityMah(), b.capacityMah())) {
                total += 1;
            } else {
                conflict = true;
                evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH,
                        "capacity_mah " + a.capacityMah() + " vs " + b.capacityMah()));
            }
        }
        for (Object[] row : new Object[][]{{"voltage_v", a.voltage(), b.voltage()}, {"ounces", a.ounces(), b.ounces()}}) {
            if (row[1] != null && row[2] != null) {
                compared++;
                double x = (Double) row[1];
                double y = (Double) row[2];
                if (Math.abs(x - y) <= 0.03 * Math.max(x, y)) {
                    total += 1;
                } else {
                    conflict = true;
                    evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH,
                            row[0] + " " + number(x) + " vs " + number(y)));
                }
            }
        }
        if (a.color() != null && b.color() != null) {
            compared++;
            if (a.color().equals(b.color())) {
                total += 1;
            } else if (Objects.equals(ColorVocabulary.family(a.color()), ColorVocabulary.family(b.color()))) {
                total += 0.8;
                evidence.add(MatchEvidence.supports("COLOR_SAME_FAMILY", a.color() + " ~ " + b.color()));
            } else {
                conflict = true;
                evidence.add(new MatchEvidence("VARIANT_CONFLICT", Effect.FORCES_NO_MATCH, "color " + a.color() + " vs " + b.color()));
            }
        }
        if (compared == 0) {
            return new AttributeComparison(null, false);
        }
        if (!conflict) {
            evidence.add(MatchEvidence.supports("ATTRIBUTES_AGREE", compared + " compared"));
        }
        return new AttributeComparison(total / compared, conflict);
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static Double categoryScore(ProductFeatures a, ProductFeatures b) {
        if (a.category() == null || b.category() == null) {
            return null;
        }
        if (Objects.equals(a.categoryLeaf(), b.categoryLeaf())) {
            return 1.0;
        }
        // Sources use different taxonomies and depths; overlap (not Jaccard) tolerates "Electronics > Smart Home >
        // Speakers" vs "Smart Speakers". No shared word means the taxonomies cannot be compared: unknown, not 0.
        Set<String> wa = new HashSet<>(List.of(a.category().replace(" > ", " ").split(" ")));
        Set<String> wb = new HashSet<>(List.of(b.category().replace(" > ", " ").split(" ")));
        double overlap = Similarity.overlap(wa, wb);
        return overlap == 0.0 ? null : overlap;
    }

    private static Set<String> variantKeys(ProductAttributes attributes) {
        Set<String> keys = new java.util.TreeSet<>(attributes.asMap().keySet());
        keys.retainAll(Set.of("storage_gb", "ram_gb", "color", "size", "pack_count", "screen_in", "volume_ml"));
        return keys;
    }

    private static Set<String> tiers(ProductFeatures f) {
        Set<String> t = new HashSet<>();
        for (String w : f.normalizedTitle().split(" ")) {
            if (TIER_WORDS.contains(w)) {
                t.add(w);
            }
        }
        return t;
    }

    private static boolean numberMismatch(ProductFeatures a, ProductFeatures b) {
        return !onlyIn(a.bareNumbers(), b.bareNumbers()).isEmpty() && !onlyIn(b.bareNumbers(), a.bareNumbers()).isEmpty();
    }

    private static Set<String> onlyIn(Set<String> x, Set<String> y) {
        Set<String> r = new java.util.TreeSet<>(x);
        r.removeAll(y);
        return r;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private record ModelComparison(Double score, boolean agrees, boolean conflict) {
    }

    private record AttributeComparison(Double score, boolean conflict) {
    }
}
