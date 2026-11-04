package io.catalogsentinel.domain.evaluation;

import io.catalogsentinel.domain.matching.CandidateBlocker;
import io.catalogsentinel.domain.matching.RuleBasedMatcher;
import io.catalogsentinel.domain.matching.TitleOnlyMatcher;
import io.catalogsentinel.domain.testsupport.RepoFiles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for matching quality. Floors sit slightly below the measured values recorded in
 * docs/benchmarks/matching so that a change that degrades quality fails the build. Raise them deliberately
 * when the matcher improves; never lower them to make a build pass.
 */
class GoldenSetEvaluationTest {

    @ParameterizedTest
    @ValueSource(strings = {"tests/golden-matching/golden-listings.tsv", "tests/golden-matching/golden-holdout.tsv"})
    void qualityFloors(String file) {
        List<LabeledListing> listings = LabeledListingsTsv.read(RepoFiles.locate(file));
        Set<CandidateBlocker.Pair> candidates = new CandidateBlocker().candidates(MatcherEvaluator.features(listings));

        MatcherEvaluator.RetrievalReport retrieval = MatcherEvaluator.retrieval(listings, candidates);
        assertThat(retrieval.recall()).as("blocking must keep every true pair").isEqualTo(1.0);
        assertThat(retrieval.reductionRatio()).as("blocking must prune most comparisons").isGreaterThan(0.90);

        MatcherEvaluator.Report rules = MatcherEvaluator.endToEnd(listings, candidates, new RuleBasedMatcher());
        MatcherEvaluator.Report baseline = MatcherEvaluator.endToEnd(listings, candidates, new TitleOnlyMatcher());

        assertThat(rules.autoOnly().precision()).as("auto-match precision").isGreaterThanOrEqualTo(0.99);
        assertThat(rules.autoOnly().recall()).as("auto-match recall").isGreaterThanOrEqualTo(0.40);
        assertThat(rules.assisted().f1()).as("assisted F1").isGreaterThanOrEqualTo(0.80);
        assertThat(rules.assisted().f1()).as("rules beat the title baseline by a wide margin")
                .isGreaterThan(baseline.assisted().f1() + 0.30);
    }
}
