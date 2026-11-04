package io.catalogsentinel.domain.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalizes category paths written as {@code "Electronics > Headphones"}, {@code "electronics/headphones"}
 * or {@code "Electronics|Headphones"} into {@code electronics > headphones}.
 */
public final class CategoryNormalizer {

    private static final Pattern SEPARATORS = Pattern.compile("\\s*(?:>|/|\\||\\\\)\\s*");

    private CategoryNormalizer() {
    }

    public static String normalize(String category) {
        if (category == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String raw : SEPARATORS.split(category)) {
            String p = TextNormalizer.basic(raw);
            if (p != null) {
                parts.add(p);
            }
        }
        return parts.isEmpty() ? null : String.join(" > ", parts);
    }

    /** The most specific segment of a normalized path ({@code "electronics > headphones"} gives {@code headphones}). */
    public static String leaf(String normalizedPath) {
        if (normalizedPath == null) {
            return null;
        }
        int idx = normalizedPath.lastIndexOf(" > ");
        return idx < 0 ? normalizedPath : normalizedPath.substring(idx + 3);
    }
}
