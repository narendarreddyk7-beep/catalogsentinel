package io.catalogsentinel.domain.listing;

/**
 * Result of applying one listing event. Persisted in {@code processed_events} so that replays of the same
 * event return exactly the same outcome.
 */
public enum IngestionOutcome {
    /** A new active listing was created. */
    CREATED,
    /** A newer version changed the listing content. */
    UPDATED,
    /** Accepted without a content or status change (version may have advanced). */
    UNCHANGED,
    /** The listing became (or was first recorded as) a withdrawn tombstone. */
    WITHDRAWN,
    /** A newer upsert brought a withdrawn listing back. */
    REACTIVATED,
    /** The event's version is older than the stored version; ignored. */
    STALE_IGNORED,
    /** Same version as stored but different content or status; rejected. */
    VERSION_CONFLICT;

    /** Whether the outcome means the event was accepted (as opposed to rejected as a conflict). */
    public boolean accepted() {
        return this != VERSION_CONFLICT;
    }

    /** Whether downstream consumers (matching, gaps) must re-evaluate the listing. */
    public boolean changesCatalog() {
        return this == CREATED || this == UPDATED || this == WITHDRAWN || this == REACTIVATED;
    }
}
