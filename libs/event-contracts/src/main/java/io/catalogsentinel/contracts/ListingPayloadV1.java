package io.catalogsentinel.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Listing content carried by {@code LISTING_UPSERTED} events. Attributes are flat string pairs
 * (for example {@code "color": "Black"}, {@code "storage": "256 GB"}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingPayloadV1(
        String title,
        String brand,
        String model,
        String category,
        String gtin,
        Map<String, String> attributes) {
}
