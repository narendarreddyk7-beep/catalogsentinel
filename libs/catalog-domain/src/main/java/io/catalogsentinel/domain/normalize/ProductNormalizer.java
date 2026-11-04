package io.catalogsentinel.domain.normalize;

import io.catalogsentinel.domain.listing.ListingContent;

import java.util.Map;

/** Facade that turns raw {@link ListingContent} into a {@link NormalizedListing}. */
public final class ProductNormalizer {

    /** Version tag persisted with normalized data so a normalization change can trigger re-normalization. */
    public static final String VERSION = "norm-v2";

    private ProductNormalizer() {
    }

    public static NormalizedListing normalize(ListingContent content) {
        return normalize(content.title(), content.brand(), content.model(), content.category(), content.attributes());
    }

    public static NormalizedListing normalize(String title, String brand, String model, String category,
                                              Map<String, String> attributes) {
        String normalizedTitle = TextNormalizer.normalizeTitle(title);
        String normalizedCategory = CategoryNormalizer.normalize(category);
        return new NormalizedListing(
                normalizedTitle,
                BrandNormalizer.normalize(brand),
                ModelNormalizer.normalize(model),
                normalizedCategory,
                CategoryNormalizer.leaf(normalizedCategory),
                AttributeExtractor.extract(normalizedTitle, attributes));
    }
}
