package io.catalogsentinel.domain.gap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an uncovered reference product is a selection gap, how confident that conclusion is, and
 * how urgent it is. Pure and deterministic; every number is accompanied by a human-readable explanation.
 *
 * <p>Confidence reflects how sure we are that the marketplace really lacks the product:
 * <ul>
 *   <li>no candidate at all: 0.95;</li>
 *   <li>a reviewer rejected the closest candidate: 0.92;</li>
 *   <li>closest candidate is a sibling variant (variant/tier/model conflict): 0.90;</li>
 *   <li>closest candidate was judged NO_MATCH: {@code 0.95 - 0.5 * score}, within [0.5, 0.95];</li>
 *   <li>closest candidate awaits match review: {@code 0.45 - (score - 0.7)}, within [0.25, 0.45]. Calibrated on
 *       the development set, where about 58% of REVIEW-band candidates were true matches: "probably missing" must
 *       therefore stay below 0.5 while a plausible candidate is unreviewed.</li>
 * </ul>
 *
 * <p>Priority score (0-100) = {@code 100 * (0.5 * confidence + 0.3 * demand + 0.2 * referenceCoverage)} where
 * demand is a <b>synthetic</b> demand index supplied with reference data (0.5 "unknown" when absent; the
 * platform has no access to real sales or demand data) and reference coverage is the share of reference
 * sources that carry the product. P1 at 70+, P2 at 50+, otherwise P3.
 */
public final class GapAssessor {

    public static final String VERSION = "gap-v1";
    static final Set<String> SIBLING_VARIANT_EVIDENCE = Set.of("VARIANT_CONFLICT", "TIER_MISMATCH", "MODEL_CONFLICT");

    private GapAssessor() {
    }

    /**
     * @param bestCandidate the highest-scoring candidate pair between the product's reference listings and the
     *                      marketplace's active listings, or {@code null} if retrieval found none
     */
    public record Input(BestCandidate bestCandidate, int referenceSourcesCarrying, int referenceSourcesTotal,
                        Double syntheticDemandIndex) {
    }

    public record BestCandidate(long listingId, double score, String decision, String reviewStatus,
                                Set<String> evidenceCodes) {
    }

    public record Assessment(GapReason reason, double confidence, double demand, boolean demandKnown,
                             double referenceCoverage, double priorityScore, String priority, List<String> explanation) {
    }

    public static Assessment assess(Input in) {
        List<String> why = new ArrayList<>();
        GapReason reason;
        double confidence;
        BestCandidate best = in.bestCandidate();
        if (best == null) {
            reason = GapReason.NO_CANDIDATE;
            confidence = 0.95;
            why.add("No similar listing was retrieved from the marketplace (GTIN, brand+model and title search).");
        } else if ("REJECTED".equals(best.reviewStatus())) {
            reason = GapReason.CANDIDATE_REJECTED;
            confidence = 0.92;
            why.add(String.format(Locale.ROOT, "A reviewer rejected the closest candidate (listing %d, score %.2f) "
                    + "as a different product.", best.listingId(), best.score()));
        } else if ("NO_MATCH".equals(best.decision())
                && best.evidenceCodes().stream().anyMatch(SIBLING_VARIANT_EVIDENCE::contains)) {
            reason = GapReason.VARIANT_NOT_CARRIED;
            confidence = 0.90;
            why.add(String.format(Locale.ROOT, "The marketplace carries a related variant (listing %d) but not this "
                    + "one: %s.", best.listingId(), String.join(", ", best.evidenceCodes().stream()
                    .filter(SIBLING_VARIANT_EVIDENCE::contains).sorted().toList())));
        } else if ("NO_MATCH".equals(best.decision())) {
            reason = GapReason.ONLY_NON_MATCHING_CANDIDATES;
            confidence = clamp(0.95 - 0.5 * best.score(), 0.5, 0.95);
            why.add(String.format(Locale.ROOT, "The closest marketplace listing (%d) scored %.2f, below the review "
                    + "threshold.", best.listingId(), best.score()));
        } else {
            reason = GapReason.UNCONFIRMED_CANDIDATE;
            confidence = clamp(0.45 - (best.score() - 0.7), 0.25, 0.45);
            why.add(String.format(Locale.ROOT, "Listing %d may be the same product (score %.2f) and is awaiting match "
                    + "review; reviewing it may close this gap.", best.listingId(), best.score()));
        }

        boolean demandKnown = in.syntheticDemandIndex() != null;
        double demand = demandKnown ? clamp(in.syntheticDemandIndex(), 0, 1) : 0.5;
        why.add(demandKnown
                ? String.format(Locale.ROOT, "Synthetic demand index %.2f (generated test signal, not real demand).", demand)
                : "No demand signal available; neutral 0.50 used.");
        double coverage = in.referenceSourcesTotal() <= 0 ? 0
                : clamp((double) in.referenceSourcesCarrying() / in.referenceSourcesTotal(), 0, 1);
        why.add(String.format(Locale.ROOT, "Carried by %d of %d reference sources.", in.referenceSourcesCarrying(),
                in.referenceSourcesTotal()));

        double priorityScore = Math.round(100 * (0.5 * confidence + 0.3 * demand + 0.2 * coverage) * 100) / 100.0;
        String priority = priorityScore >= 70 ? "P1" : priorityScore >= 50 ? "P2" : "P3";
        return new Assessment(reason, round4(confidence), demand, demandKnown, round4(coverage), priorityScore,
                priority, List.copyOf(why));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
