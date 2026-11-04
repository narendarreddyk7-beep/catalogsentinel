package io.catalogsentinel.domain.matching;

/** Three-way classification of a candidate pair. */
public enum MatchDecision {
    /** High confidence; linked to the same canonical product without human review. */
    AUTO_MATCH,
    /** Plausible but uncertain, or blocked from auto-matching by a rule; queued for a reviewer. */
    REVIEW,
    /** Not the same product. Kept as evidence (for example "closest candidate was a different variant"). */
    NO_MATCH
}
