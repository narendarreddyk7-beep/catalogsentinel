package io.catalogsentinel.core.matching;

import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.matching.ProductFeatures;
import io.catalogsentinel.domain.source.SourceRole;

/** A listing loaded for matching: its features plus the facts linking and gap detection need. */
public record MatchableListing(
        long listingId,
        long sourceId,
        SourceRole sourceRole,
        ListingStatus status,
        String contentHash,
        String title,
        String brand,
        String model,
        String category,
        String gtin,
        String extractedAttributesJson,
        ProductFeatures features) {
}
