package io.catalogsentinel.domain.matching;

/**
 * Decision thresholds on the overall score: {@code score >= autoMatch} may auto-match (subject to the rules),
 * {@code score >= review} goes to human review, anything lower is not a match.
 */
public record MatchThresholds(double autoMatch, double review) {

    public static final MatchThresholds DEFAULT = new MatchThresholds(0.90, 0.70);

    public MatchThresholds {
        if (!(0 < review && review < autoMatch && autoMatch <= 1)) {
            throw new IllegalArgumentException("require 0 < review < autoMatch <= 1");
        }
    }
}
