package io.catalogsentinel.domain.evaluation;

import io.catalogsentinel.domain.matching.CandidateBlocker;
import io.catalogsentinel.domain.matching.MatchDecision;
import io.catalogsentinel.domain.matching.MatchResult;
import io.catalogsentinel.domain.matching.ProductMatcher;
import io.catalogsentinel.domain.matching.ProductFeatures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measures matcher quality against labeled listings.
 *
 * <p>Two prediction policies are reported because they answer different questions:
 * <ul>
 *   <li><b>auto-only</b>: a pair is predicted to match only on {@code AUTO_MATCH}. Precision here is what
 *       the catalog would see without any human involvement.</li>
 *   <li><b>assisted</b>: {@code AUTO_MATCH} or {@code REVIEW}. Recall here is the share of true matches the
 *       platform surfaces at all (automatically or to a reviewer).</li>
 * </ul>
 * Pairwise evaluation compares every pair (independent of blocking); end-to-end evaluation treats pairs that
 * candidate retrieval never proposed as predicted non-matches.
 */
public final class MatcherEvaluator {

    private MatcherEvaluator() {
    }

    public record Report(
            String matcherVersion,
            String scope,
            long pairsEvaluated,
            long positivePairs,
            ConfusionMatrix autoOnly,
            ConfusionMatrix assisted,
            long reviewDecisions,
            Map<String, Long> autoFalsePositivesByKind,
            Map<String, Long> assistedFalsePositivesByKind,
            List<String> autoFalsePositiveExamples,
            List<String> missedMatchExamples) {
    }

    public record RetrievalReport(long listings, long allPairs, long candidatePairs, long positivePairs,
                                  long positivesRetrieved) {
        public double recall() {
            return positivePairs == 0 ? 1.0 : (double) positivesRetrieved / positivePairs;
        }

        public double reductionRatio() {
            return allPairs == 0 ? 0.0 : 1.0 - (double) candidatePairs / allPairs;
        }
    }

    /** Compares every unordered pair. Quadratic: intended for golden sets of a few hundred listings. */
    public static Report allPairs(List<LabeledListing> listings, ProductMatcher matcher) {
        Accumulator acc = new Accumulator(matcher.version(), "all-pairs");
        for (int i = 0; i < listings.size(); i++) {
            for (int j = i + 1; j < listings.size(); j++) {
                acc.add(listings.get(i), listings.get(j), matcher.compare(listings.get(i).features(), listings.get(j).features()));
            }
        }
        return acc.report();
    }

    /** End-to-end: only retrieved candidate pairs are scored; every other pair counts as predicted non-match. */
    public static Report endToEnd(List<LabeledListing> listings, Set<CandidateBlocker.Pair> candidates,
                                  ProductMatcher matcher) {
        Map<String, LabeledListing> byId = new HashMap<>();
        listings.forEach(l -> byId.put(l.id(), l));
        Accumulator acc = new Accumulator(matcher.version(), "blocking+matcher");
        for (CandidateBlocker.Pair p : candidates) {
            LabeledListing a = byId.get(p.left());
            LabeledListing b = byId.get(p.right());
            acc.add(a, b, matcher.compare(a.features(), b.features()));
        }
        long positives = countPositivePairs(listings);
        long retrievedPositives = acc.positives;
        long missedByRetrieval = positives - retrievedPositives;
        for (long k = 0; k < missedByRetrieval; k++) {
            acc.autoOnly.record(false, true);
            acc.assisted.record(false, true);
        }
        acc.positives = positives;
        long total = (long) listings.size() * (listings.size() - 1) / 2;
        long negativesNotRetrieved = total - candidates.size() - missedByRetrieval;
        for (long k = 0; k < negativesNotRetrieved; k++) {
            acc.autoOnly.record(false, false);
            acc.assisted.record(false, false);
        }
        acc.pairs = total;
        return acc.report();
    }

    public static RetrievalReport retrieval(List<LabeledListing> listings, Set<CandidateBlocker.Pair> candidates) {
        Map<String, String> truth = new HashMap<>();
        listings.forEach(l -> truth.put(l.id(), l.truthKey()));
        long retrieved = candidates.stream().filter(p -> truth.get(p.left()).equals(truth.get(p.right()))).count();
        long n = listings.size();
        return new RetrievalReport(n, n * (n - 1) / 2, candidates.size(), countPositivePairs(listings), retrieved);
    }

    public static long countPositivePairs(List<LabeledListing> listings) {
        Map<String, Long> sizes = new HashMap<>();
        listings.forEach(l -> sizes.merge(l.truthKey(), 1L, Long::sum));
        return sizes.values().stream().mapToLong(c -> c * (c - 1) / 2).sum();
    }

    public static List<ProductFeatures> features(List<LabeledListing> listings) {
        return listings.stream().map(LabeledListing::features).toList();
    }

    private static final class Accumulator {
        final String version;
        final String scope;
        final ConfusionMatrix autoOnly = new ConfusionMatrix();
        final ConfusionMatrix assisted = new ConfusionMatrix();
        final Map<String, Long> autoFpByKind = new TreeMap<>();
        final Map<String, Long> assistedFpByKind = new TreeMap<>();
        final List<String> autoFpExamples = new ArrayList<>();
        final List<String> missedExamples = new ArrayList<>();
        long pairs;
        long positives;
        long reviews;

        Accumulator(String version, String scope) {
            this.version = version;
            this.scope = scope;
        }

        void add(LabeledListing a, LabeledListing b, MatchResult r) {
            boolean actual = a.truthKey().equals(b.truthKey());
            boolean auto = r.decision() == MatchDecision.AUTO_MATCH;
            boolean surfaced = auto || r.decision() == MatchDecision.REVIEW;
            pairs++;
            if (actual) {
                positives++;
            }
            if (r.decision() == MatchDecision.REVIEW) {
                reviews++;
            }
            autoOnly.record(auto, actual);
            assisted.record(surfaced, actual);
            String kind = a.family().equals(b.family()) ? "hard_negative_same_family" : "negative_other_family";
            if (auto && !actual) {
                autoFpByKind.merge(kind, 1L, Long::sum);
                if (autoFpExamples.size() < 25) {
                    autoFpExamples.add(a.id() + " ~ " + b.id() + " score=" + fmt(r.score()) + " " + codes(r));
                }
            }
            if (surfaced && !actual) {
                assistedFpByKind.merge(kind, 1L, Long::sum);
            }
            if (actual && !surfaced && missedExamples.size() < 25) {
                missedExamples.add(a.id() + " ~ " + b.id() + " score=" + fmt(r.score()) + " " + codes(r));
            }
        }

        Report report() {
            return new Report(version, scope, pairs, positives, autoOnly, assisted, reviews, autoFpByKind,
                    assistedFpByKind, autoFpExamples, missedExamples);
        }

        private static String codes(MatchResult r) {
            return r.evidence().stream().map(e -> e.code()).distinct().toList().toString();
        }

        private static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.3f", v);
        }
    }
}
