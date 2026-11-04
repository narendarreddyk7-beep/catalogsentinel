package io.catalogsentinel.domain.evaluation;

import io.catalogsentinel.domain.matching.CandidateBlocker;
import io.catalogsentinel.domain.matching.ProductMatcher;
import io.catalogsentinel.domain.matching.RuleBasedMatcher;
import io.catalogsentinel.domain.matching.TitleOnlyMatcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Prints a Markdown evaluation report for a labeled TSV:
 * {@code java -cp catalog-domain.jar io.catalogsentinel.domain.evaluation.EvaluationCli tests/golden-matching/golden-listings.tsv [--no-all-pairs]}.
 * All-pairs evaluation is quadratic; pass {@code --no-all-pairs} for large synthetic sets.
 */
public final class EvaluationCli {

    private EvaluationCli() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: EvaluationCli <labeled.tsv> [--no-all-pairs]");
            System.exit(2);
        }
        boolean allPairs = !List.of(args).contains("--no-all-pairs");
        long t0 = System.nanoTime();
        List<LabeledListing> listings = LabeledListingsTsv.read(Path.of(args[0]));
        long t1 = System.nanoTime();
        Set<CandidateBlocker.Pair> candidates = new CandidateBlocker().candidates(MatcherEvaluator.features(listings));
        long t2 = System.nanoTime();
        MatcherEvaluator.RetrievalReport retrieval = MatcherEvaluator.retrieval(listings, candidates);

        System.out.println("# Matching evaluation: " + args[0]);
        System.out.printf(Locale.ROOT, "%n- listings: %d, positive pairs: %d, all pairs: %d%n", listings.size(),
                retrieval.positivePairs(), retrieval.allPairs());
        System.out.printf(Locale.ROOT, "- load+normalize: %.1f ms, candidate generation: %.1f ms%n",
                (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        System.out.printf(Locale.ROOT, "%n## Candidate retrieval (blocking)%n%n| candidate pairs | reduction ratio | positive-pair recall |%n|---|---|---|%n| %d | %.4f | %.4f (%d/%d) |%n",
                retrieval.candidatePairs(), retrieval.reductionRatio(), retrieval.recall(),
                retrieval.positivesRetrieved(), retrieval.positivePairs());

        System.out.println("\n## Matchers\n");
        System.out.println("| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|");
        for (ProductMatcher m : List.of(new TitleOnlyMatcher(), new RuleBasedMatcher())) {
            if (allPairs) {
                long s = System.nanoTime();
                MatcherEvaluator.Report r = MatcherEvaluator.allPairs(listings, m);
                row(r, (System.nanoTime() - s) / 1e6);
            }
            long s = System.nanoTime();
            MatcherEvaluator.Report e2e = MatcherEvaluator.endToEnd(listings, candidates, m);
            row(e2e, (System.nanoTime() - s) / 1e6);
        }
        if (allPairs) {
            MatcherEvaluator.Report rules = MatcherEvaluator.allPairs(listings, new RuleBasedMatcher());
            System.out.println("\n## Rule-based matcher: error analysis (all pairs)\n");
            System.out.println("- auto-only false positives by kind: " + rules.autoFalsePositivesByKind());
            System.out.println("- assisted false positives by kind: " + rules.assistedFalsePositivesByKind());
            System.out.println("\nAuto-match false positives:");
            rules.autoFalsePositiveExamples().forEach(x -> System.out.println("- " + x));
            System.out.println("\nTrue matches not surfaced (neither AUTO_MATCH nor REVIEW):");
            rules.missedMatchExamples().forEach(x -> System.out.println("- " + x));
        }
    }

    private static void row(MatcherEvaluator.Report r, double millis) {
        for (String policy : List.of("auto-only", "assisted")) {
            ConfusionMatrix cm = policy.equals("auto-only") ? r.autoOnly() : r.assisted();
            System.out.printf(Locale.ROOT, "| %s | %s | %s | %.4f | %.4f | %.4f | %d | %d | %d | %d | %.1f |%n",
                    r.matcherVersion(), r.scope(), policy, cm.precision(), cm.recall(), cm.f1(), cm.truePositives(),
                    cm.falsePositives(), cm.falseNegatives(), r.reviewDecisions(), millis);
        }
    }
}
