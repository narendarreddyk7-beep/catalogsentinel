package io.catalogsentinel.domain.matching;

import io.catalogsentinel.domain.normalize.TextNormalizer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Baseline: token Jaccard similarity of lightly normalized titles (case and punctuation only, no unit
 * canonicalization, no identifiers, no variant rules). This is what a first-cut "fuzzy title match" looks
 * like and is the yardstick the rule-based matcher is evaluated against.
 */
public final class TitleOnlyMatcher implements ProductMatcher {

    public static final String VERSION = "title-baseline-v1";
    private final MatchThresholds thresholds;

    public TitleOnlyMatcher() {
        this(new MatchThresholds(0.80, 0.60));
    }

    public TitleOnlyMatcher(MatchThresholds thresholds) {
        this.thresholds = thresholds;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public MatchResult compare(ProductFeatures a, ProductFeatures b) {
        double score = Similarity.jaccard(tokens(a), tokens(b));
        MatchDecision decision = score >= thresholds.autoMatch() ? MatchDecision.AUTO_MATCH
                : score >= thresholds.review() ? MatchDecision.REVIEW : MatchDecision.NO_MATCH;
        return new MatchResult(score, decision, new FeatureScores(null, null, null, score, null, null),
                List.of(MatchEvidence.supports("TITLE_JACCARD", String.format(java.util.Locale.ROOT, "%.3f", score))),
                VERSION);
    }

    private static Set<String> tokens(ProductFeatures f) {
        // Uses the raw-ish title: basic folding only, deliberately without unit/variant canonicalization.
        String basic = TextNormalizer.basic(f.rawTitle());
        Set<String> t = new LinkedHashSet<>();
        if (basic != null) {
            t.addAll(List.of(basic.split(" ")));
        }
        return t;
    }
}
