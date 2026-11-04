package io.catalogsentinel.domain.listing;

import java.util.Objects;

/**
 * Pure decision function for version-aware listing ingestion.
 *
 * <p>The rules, in order (cv = current version, ev = event version):
 * <ol>
 *   <li>No listing yet: an upsert creates it; a withdrawal records a tombstone at {@code ev}, so that
 *       out-of-order earlier versions arriving later are recognized as stale.</li>
 *   <li>{@code ev < cv}: stale, ignored. An old event can never overwrite newer data or resurrect a
 *       withdrawn listing.</li>
 *   <li>{@code ev == cv}: identical content (or a repeated withdrawal) is a no-op; anything else is a
 *       same-version conflict and is rejected, because the source has published two different facts under
 *       one version.</li>
 *   <li>{@code ev > cv}: the event wins. Upserts update or reactivate; withdrawals tombstone. A newer
 *       version with identical content only advances the version.</li>
 * </ol>
 *
 * <p>Event-id idempotency (exact replays, id reuse) is enforced before this function is consulted, by the
 * {@code processed_events} table. Keeping this function free of I/O makes every rule unit-testable and lets
 * the persistence layer focus on locking.
 */
public final class ListingEventDecider {

    private ListingEventDecider() {
    }

    /**
     * @param current the locked current state, or {@code null} when the listing does not exist yet
     * @param event   the validated incoming event
     */
    public static ListingDecision decide(ListingState current, IncomingListingEvent event) {
        Objects.requireNonNull(event, "event");
        long ev = event.sourceVersion();
        boolean upsert = event.type() == ListingEventType.LISTING_UPSERTED;

        if (current == null) {
            return upsert
                    ? new ListingDecision(IngestionOutcome.CREATED, ListingStatus.ACTIVE, ev, true,
                    "first version of the listing")
                    : new ListingDecision(IngestionOutcome.WITHDRAWN, ListingStatus.WITHDRAWN, ev, false,
                    "withdrawal for an unseen listing recorded as a tombstone");
        }

        long cv = current.version();
        if (ev < cv) {
            return new ListingDecision(IngestionOutcome.STALE_IGNORED, current.status(), cv, false,
                    "event version " + ev + " is older than stored version " + cv);
        }

        if (ev == cv) {
            if (upsert) {
                if (current.status() == ListingStatus.ACTIVE
                        && Objects.equals(current.contentHash(), event.contentHash())) {
                    return new ListingDecision(IngestionOutcome.UNCHANGED, ListingStatus.ACTIVE, cv, false,
                            "identical content already stored at version " + cv);
                }
                String why = current.status() == ListingStatus.WITHDRAWN
                        ? "listing is withdrawn at version " + cv + "; reactivation requires a newer version"
                        : "different content was already stored at version " + cv;
                return new ListingDecision(IngestionOutcome.VERSION_CONFLICT, current.status(), cv, false, why);
            }
            if (current.status() == ListingStatus.WITHDRAWN) {
                return new ListingDecision(IngestionOutcome.UNCHANGED, ListingStatus.WITHDRAWN, cv, false,
                        "already withdrawn at version " + cv);
            }
            return new ListingDecision(IngestionOutcome.VERSION_CONFLICT, ListingStatus.ACTIVE, cv, false,
                    "withdrawal must carry a version newer than the active content (version " + cv + ")");
        }

        // ev > cv: the event is newer than anything stored.
        if (upsert) {
            if (current.status() == ListingStatus.WITHDRAWN) {
                return new ListingDecision(IngestionOutcome.REACTIVATED, ListingStatus.ACTIVE, ev, true,
                        "newer version " + ev + " reactivates a listing withdrawn at version " + cv);
            }
            if (Objects.equals(current.contentHash(), event.contentHash())) {
                return new ListingDecision(IngestionOutcome.UNCHANGED, ListingStatus.ACTIVE, ev, false,
                        "content unchanged; version advanced from " + cv + " to " + ev);
            }
            return new ListingDecision(IngestionOutcome.UPDATED, ListingStatus.ACTIVE, ev, true,
                    "content updated from version " + cv + " to " + ev);
        }
        if (current.status() == ListingStatus.WITHDRAWN) {
            return new ListingDecision(IngestionOutcome.UNCHANGED, ListingStatus.WITHDRAWN, ev, false,
                    "already withdrawn; tombstone version advanced from " + cv + " to " + ev);
        }
        return new ListingDecision(IngestionOutcome.WITHDRAWN, ListingStatus.WITHDRAWN, ev, false,
                "withdrawn at version " + ev);
    }
}
