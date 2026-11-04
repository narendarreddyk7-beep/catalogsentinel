package io.catalogsentinel.core.listing;

import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.source.SourceRole;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read model of a listing, including its source and canonical product link (if any). */
public record ListingRecord(
        long listingId,
        long sourceId,
        String sourceCode,
        SourceRole sourceRole,
        String externalProductId,
        ListingStatus status,
        long sourceVersion,
        String title,
        String brand,
        String model,
        String category,
        String gtin,
        Map<String, String> attributes,
        String normalizedTitle,
        String normalizedBrand,
        String normalizedModel,
        String categoryLeaf,
        Map<String, String> extractedAttributes,
        String contentHash,
        UUID lastEventId,
        Instant sourceUpdatedAt,
        Instant withdrawnAt,
        Instant createdAt,
        Instant updatedAt,
        Long canonicalProductId,
        Double linkConfidence,
        String linkMethod) {
}
