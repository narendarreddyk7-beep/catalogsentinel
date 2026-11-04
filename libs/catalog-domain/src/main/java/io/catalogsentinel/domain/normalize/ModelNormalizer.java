package io.catalogsentinel.domain.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Canonicalizes manufacturer model / part numbers to lowercase alphanumerics
 * ({@code "WH-1000XM5"} becomes {@code wh1000xm5}) and exposes the digit groups that distinguish
 * generations and capacities ({@code wh1000xm5} has digit groups {@code [1000, 5]}).
 */
public final class ModelNormalizer {

    /**
     * Words that denote a different product tier when appended to a model, so they must never be treated as
     * a harmless variant suffix ("iphone15" vs "iphone15pro").
     */
    private static final Set<String> TIER_WORDS = Set.of(
            "pro", "max", "plus", "mini", "ultra", "lite", "air", "se", "xl", "xs", "neo", "fe", "edge");

    private ModelNormalizer() {
    }

    public static String normalize(String model) {
        if (model == null) {
            return null;
        }
        // Same Mark/Gen canonicalization as titles, so "EOS R6 Mark II" (eosr6mk2) and "EOS R6" (eosr6)
        // differ in their digit groups.
        String s = TextNormalizer.canonicalizeUnits(TextNormalizer.fold(model)).replaceAll("[^a-z0-9]", "");
        return s.isEmpty() ? null : s;
    }

    /** Maximal runs of digits, in order of appearance. */
    public static List<String> digitGroups(String normalizedModel) {
        List<String> groups = new ArrayList<>();
        if (normalizedModel == null) {
            return groups;
        }
        int i = 0;
        while (i < normalizedModel.length()) {
            if (Character.isDigit(normalizedModel.charAt(i))) {
                int start = i;
                while (i < normalizedModel.length() && Character.isDigit(normalizedModel.charAt(i))) {
                    i++;
                }
                groups.add(stripLeadingZeros(normalizedModel.substring(start, i)));
            } else {
                i++;
            }
        }
        return groups;
    }

    /**
     * Whether {@code longer} is {@code shorter} plus a short alphabetic regional/packaging suffix such as
     * Apple's {@code ll/a} or Samsung's {@code /ds}: same product, different sales variant code.
     */
    public static boolean isVariantSuffix(String shorter, String longer) {
        if (shorter == null || longer == null || shorter.length() < 4 || !longer.startsWith(shorter)
                || longer.length() == shorter.length()) {
            return false;
        }
        String rest = longer.substring(shorter.length());
        if (rest.length() > 3 || TIER_WORDS.contains(rest)) {
            return false;
        }
        for (int i = 0; i < rest.length(); i++) {
            if (!Character.isLetter(rest.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
