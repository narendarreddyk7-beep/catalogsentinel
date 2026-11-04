package io.catalogsentinel.domain.listing;

import java.util.Objects;

/**
 * What the ingestion rules decided for one event against the current listing state.
 *
 * @param outcome          classified outcome
 * @param resultingStatus  status after applying the event (equals the current status when nothing changes)
 * @param resultingVersion version after applying the event (equals the current version when nothing changes)
 * @param replaceContent   whether the stored content must be replaced with the event's content
 * @param reason           human-readable explanation, surfaced in API responses and audit logs
 */
public record ListingDecision(
        IngestionOutcome outcome,
        ListingStatus resultingStatus,
        long resultingVersion,
        boolean replaceContent,
        String reason) {

    public ListingDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(resultingStatus, "resultingStatus");
        Objects.requireNonNull(reason, "reason");
    }

    /** Whether the stored listing row must be written (insert or update). */
    public boolean requiresWrite(ListingState current) {
        if (!outcome.accepted() || outcome == IngestionOutcome.STALE_IGNORED) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return replaceContent
                || resultingStatus != current.status()
                || resultingVersion != current.version();
    }
}
