package io.catalogsentinel.domain.gap;

import java.util.Map;
import java.util.Optional;

/** The gap state machine as data: every allowed (status, decision) pair and its target status. */
public final class GapLifecycle {

    private static final Map<GapStatus, Map<GapDecision, GapStatus>> TRANSITIONS = Map.of(
            GapStatus.OPEN, Map.of(
                    GapDecision.CONFIRM, GapStatus.CONFIRMED,
                    GapDecision.DISMISS, GapStatus.DISMISSED,
                    GapDecision.RESOLVE, GapStatus.RESOLVED),
            GapStatus.CONFIRMED, Map.of(
                    GapDecision.DISMISS, GapStatus.DISMISSED,
                    GapDecision.RESOLVE, GapStatus.RESOLVED),
            GapStatus.DISMISSED, Map.of(
                    GapDecision.REOPEN, GapStatus.OPEN),
            GapStatus.RESOLVED, Map.of());

    private GapLifecycle() {
    }

    /** @return the new status, or empty when the transition is not allowed */
    public static Optional<GapStatus> next(GapStatus current, GapDecision decision) {
        return Optional.ofNullable(TRANSITIONS.get(current).get(decision));
    }

    /** A decision that leaves the gap where it already is (e.g. CONFIRM on CONFIRMED) is an idempotent no-op. */
    public static boolean isNoOp(GapStatus current, GapDecision decision) {
        return switch (decision) {
            case CONFIRM -> current == GapStatus.CONFIRMED;
            case DISMISS -> current == GapStatus.DISMISSED;
            case RESOLVE -> current == GapStatus.RESOLVED;
            case REOPEN -> current == GapStatus.OPEN;
        };
    }
}
