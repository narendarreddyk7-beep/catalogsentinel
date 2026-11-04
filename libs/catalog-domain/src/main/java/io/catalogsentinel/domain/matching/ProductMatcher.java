package io.catalogsentinel.domain.matching;

/** Compares two listings. Implementations must be deterministic and symmetric in their decision. */
public interface ProductMatcher {

    MatchResult compare(ProductFeatures a, ProductFeatures b);

    /** Version tag persisted with every candidate so results from different algorithms are never mixed. */
    String version();
}
