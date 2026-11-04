package io.catalogsentinel.domain.listing;

import java.util.Objects;

/**
 * The persisted facts the ingestion rules need about an existing listing.
 *
 * @param version     highest source version applied so far (content or tombstone)
 * @param status      current lifecycle status
 * @param contentHash hash of the stored content; {@code null} for a tombstone that never had content
 */
public record ListingState(long version, ListingStatus status, String contentHash) {

    public ListingState {
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }
}
