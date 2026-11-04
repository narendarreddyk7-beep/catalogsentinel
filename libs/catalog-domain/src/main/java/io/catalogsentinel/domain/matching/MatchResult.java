package io.catalogsentinel.domain.matching;

import java.util.List;

/**
 * Outcome of comparing two listings: an overall score in [0, 1], a decision, the per-feature scores and the
 * ordered evidence that produced the decision.
 */
public record MatchResult(double score, MatchDecision decision, FeatureScores features, List<MatchEvidence> evidence,
                          String matcherVersion) {

    public MatchResult {
        evidence = List.copyOf(evidence);
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("score out of range: " + score);
        }
    }

    public boolean hasEvidence(String code) {
        return evidence.stream().anyMatch(e -> e.code().equals(code));
    }
}
