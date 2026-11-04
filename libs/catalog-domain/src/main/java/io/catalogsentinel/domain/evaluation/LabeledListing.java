package io.catalogsentinel.domain.evaluation;

import io.catalogsentinel.domain.matching.ProductFeatures;

/**
 * A listing with ground truth.
 *
 * @param truthKey identifies the exact product variant; two listings match if and only if their keys are equal
 * @param family   groups related variants (e.g. all WH-1000XM headphones); pairs in one family with different
 *                 truth keys are the "hard negatives"
 */
public record LabeledListing(String id, String source, String truthKey, String family, ProductFeatures features) {
}
