package io.catalogsentinel.domain.matching;

/**
 * One explainable reason behind a match decision.
 *
 * @param code   stable machine-readable code, e.g. {@code GTIN_MATCH}, {@code VARIANT_CONFLICT}
 * @param effect whether the evidence supports or contradicts a match, or blocks a decision outright
 * @param detail human-readable detail, e.g. {@code storage_gb 128 vs 256}
 */
public record MatchEvidence(String code, Effect effect, String detail) {

    public enum Effect {
        SUPPORTS,
        CONTRADICTS,
        BLOCKS_AUTO_MATCH,
        FORCES_NO_MATCH
    }

    public static MatchEvidence supports(String code, String detail) {
        return new MatchEvidence(code, Effect.SUPPORTS, detail);
    }

    public static MatchEvidence contradicts(String code, String detail) {
        return new MatchEvidence(code, Effect.CONTRADICTS, detail);
    }
}
