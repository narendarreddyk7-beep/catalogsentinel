package io.catalogsentinel.domain.listing;

import io.catalogsentinel.domain.common.CanonicalForm;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.common.Violations;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Supplier-provided product content for one listing, as carried by an upsert event.
 *
 * <p>Values are trimmed and blank optional fields become {@code null}, so that cosmetic differences
 * (" Apple " vs "Apple") do not produce different content hashes. Attributes are restricted to
 * string key/value pairs and kept sorted, which keeps hashing deterministic.
 *
 * @param gtin canonical GTIN-14 or {@code null}; raw input is validated by {@link #of}
 */
public record ListingContent(
        String title,
        String brand,
        String model,
        String category,
        String gtin,
        SortedMap<String, String> attributes) {

    public static final int MAX_TITLE = 500;
    public static final int MAX_SHORT_FIELD = 200;
    public static final int MAX_ATTRIBUTES = 64;
    public static final int MAX_ATTRIBUTE_KEY = 64;
    public static final int MAX_ATTRIBUTE_VALUE = 500;

    public ListingContent {
        title = trimToNull(title);
        brand = trimToNull(brand);
        model = trimToNull(model);
        category = trimToNull(category);
        gtin = trimToNull(gtin);
        TreeMap<String, String> cleaned = new TreeMap<>();
        if (attributes != null) {
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                String key = trimToNull(e.getKey());
                String value = trimToNull(e.getValue());
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            }
        }
        Violations v = new Violations()
                .requireText("payload.title", title, MAX_TITLE)
                .optionalText("payload.brand", brand, MAX_SHORT_FIELD)
                .optionalText("payload.model", model, MAX_SHORT_FIELD)
                .optionalText("payload.category", category, MAX_SHORT_FIELD);
        if (gtin != null && (gtin.length() != 14 || Gtin.parse(gtin).isEmpty())) {
            v.add("payload.gtin", "must be canonical GTIN-14; use ListingContent.of to normalize raw input");
        }
        if (cleaned.size() > MAX_ATTRIBUTES) {
            v.add("payload.attributes", "must contain at most " + MAX_ATTRIBUTES + " entries");
        }
        for (Map.Entry<String, String> e : cleaned.entrySet()) {
            if (e.getKey().length() > MAX_ATTRIBUTE_KEY) {
                v.add("payload.attributes", "key '" + abbreviate(e.getKey()) + "' exceeds " + MAX_ATTRIBUTE_KEY + " characters");
            }
            if (e.getValue().length() > MAX_ATTRIBUTE_VALUE) {
                v.add("payload.attributes." + abbreviate(e.getKey()), "value exceeds " + MAX_ATTRIBUTE_VALUE + " characters");
            }
        }
        v.throwIfAny();
        attributes = Collections.unmodifiableSortedMap(cleaned);
    }

    /**
     * Builds content from raw producer input, normalizing a GTIN into canonical form.
     *
     * @throws io.catalogsentinel.domain.common.DomainValidationException if the GTIN is present but invalid
     *                                                                     or any other field is invalid
     */
    public static ListingContent of(String title, String brand, String model, String category,
                                    String rawGtin, Map<String, String> attributes) {
        java.util.List<DomainValidationException.Violation> violations = new java.util.ArrayList<>();
        String gtin = null;
        String trimmedGtin = trimToNull(rawGtin);
        if (trimmedGtin != null) {
            java.util.Optional<Gtin> parsed = Gtin.parse(trimmedGtin);
            if (parsed.isPresent()) {
                gtin = parsed.get().value();
            } else {
                violations.add(new DomainValidationException.Violation("payload.gtin",
                        "is not a valid GTIN-8/12/13/14 (check digit or length)"));
            }
        }
        ListingContent content = null;
        try {
            // Construct even when the GTIN is invalid so that every other field violation is reported too.
            content = new ListingContent(title, brand, model, category, gtin,
                    attributes == null ? null : new TreeMap<>(attributes));
        } catch (DomainValidationException e) {
            violations.addAll(0, e.violations());
        }
        if (!violations.isEmpty()) {
            throw new DomainValidationException(violations);
        }
        return content;
    }

    /** SHA-256 over the canonical form of every content field; equal content always hashes equally. */
    public String contentHash() {
        return CanonicalForm.create()
                .field("title", title)
                .field("brand", brand)
                .field("model", model)
                .field("category", category)
                .field("gtin", gtin)
                .field("attributes", attributes)
                .sha256Hex();
    }

    void writeTo(CanonicalForm form) {
        form.field("title", title)
                .field("brand", brand)
                .field("model", model)
                .field("category", category)
                .field("gtin", gtin)
                .field("attributes", attributes);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String abbreviate(String s) {
        return s.length() <= 24 ? s : s.substring(0, 24) + "...";
    }
}
