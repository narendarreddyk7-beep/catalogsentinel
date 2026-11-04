package io.catalogsentinel.domain.gap;

/** Why a reference product is considered missing from a marketplace. */
public enum GapReason {
    /** Candidate retrieval found nothing similar in the marketplace. */
    NO_CANDIDATE,
    /** Similar listings exist, but the matcher judged none to be the same product. */
    ONLY_NON_MATCHING_CANDIDATES,
    /** The marketplace carries a sibling variant (other storage, color, size...), not this one. */
    VARIANT_NOT_CARRIED,
    /** A plausible candidate awaits human match review; the gap may disappear once it is reviewed. */
    UNCONFIRMED_CANDIDATE,
    /** A reviewer rejected the closest candidate as a different product. */
    CANDIDATE_REJECTED
}
