package io.catalogsentinel.domain.gap;

/**
 * Gap lifecycle.
 *
 * <pre>
 *   OPEN ──confirm──▶ CONFIRMED ──resolve──▶ RESOLVED (terminal)
 *    │  └──resolve──────────────────────────▲
 *    └──dismiss──▶ DISMISSED ◀──dismiss── CONFIRMED
 *                    └──reopen──▶ OPEN
 * </pre>
 * A DISMISSED gap suppresses re-detection of the same (product, marketplace) pair until it is reopened.
 * A RESOLVED gap is history; if the product goes missing again, reconciliation opens a new gap.
 */
public enum GapStatus {
    OPEN,
    CONFIRMED,
    DISMISSED,
    RESOLVED;

    /** Statuses covered by the deduplication index (at most one such gap per product and marketplace). */
    public boolean isActive() {
        return this != RESOLVED;
    }
}
