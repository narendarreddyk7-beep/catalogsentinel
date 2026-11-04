package io.catalogsentinel.domain.normalize;

/**
 * The normalized view of a listing that is persisted alongside raw content and used for candidate
 * retrieval and matching.
 *
 * @param title        normalized title (units canonicalized, punctuation removed)
 * @param brand        canonical brand or {@code null}
 * @param model        canonical alphanumeric model number or {@code null}
 * @param category     normalized category path or {@code null}
 * @param categoryLeaf most specific category segment or {@code null}
 * @param attributes   extracted variant attributes
 */
public record NormalizedListing(
        String title,
        String brand,
        String model,
        String category,
        String categoryLeaf,
        ProductAttributes attributes) {
}
