package io.catalogsentinel.domain.normalize;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Recognizes color names in normalized text and maps each color to a coarse family. Two colors in the same
 * family ("space gray" and "gray") are treated as compatible descriptions; different families
 * ("black" and "blue") are a variant conflict.
 */
public final class ColorVocabulary {

    private static final List<String> COLORS = Stream.of(
                    "space gray", "space black", "rose gold", "midnight blue", "navy blue", "sky blue",
                    "light blue", "dark blue", "royal blue", "forest green", "olive green", "jet black",
                    "matte black", "pure white", "off white", "natural titanium", "black titanium",
                    "white titanium", "blue titanium", "desert titanium", "hot pink", "light pink",
                    "black", "white", "silver", "gray", "gold", "blue", "red", "green", "pink", "purple",
                    "yellow", "orange", "brown", "beige", "navy", "teal", "graphite", "midnight", "starlight",
                    "titanium", "bronze", "copper", "charcoal", "ivory", "cream", "lavender", "mint", "coral",
                    "turquoise", "burgundy", "maroon", "khaki", "olive", "tan", "champagne", "platinum",
                    "clear", "transparent", "multicolor")
            // Longest first so "space gray" wins over "gray".
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    private static final Map<String, String> FAMILY_OVERRIDES = Map.ofEntries(
            Map.entry("navy", "blue"), Map.entry("teal", "blue"), Map.entry("turquoise", "blue"),
            Map.entry("charcoal", "gray"), Map.entry("graphite", "gray"),
            Map.entry("ivory", "white"), Map.entry("cream", "white"),
            Map.entry("burgundy", "red"), Map.entry("maroon", "red"), Map.entry("coral", "red"),
            Map.entry("olive", "green"), Map.entry("mint", "green"),
            Map.entry("platinum", "silver"), Map.entry("transparent", "clear"),
            Map.entry("lavender", "purple"), Map.entry("khaki", "beige"), Map.entry("tan", "beige"));

    private ColorVocabulary() {
    }

    /**
     * The color mentioned earliest in normalized text ("black graphite" gives black), preferring the longest
     * name at that position ("space gray" over "gray"). Returns {@code null} when no color is mentioned.
     */
    public static String findIn(String normalizedText) {
        if (normalizedText == null) {
            return null;
        }
        String padded = " " + normalizedText + " ";
        String best = null;
        int bestPos = Integer.MAX_VALUE;
        for (String color : COLORS) {
            int pos = padded.indexOf(" " + color + " ");
            if (pos >= 0 && (pos < bestPos || (pos == bestPos && color.length() > best.length()))) {
                best = color;
                bestPos = pos;
            }
        }
        return best;
    }

    /** Canonicalizes a free-text color value ("Space Grey" becomes "space gray"); unknown values pass through. */
    public static String canonical(String rawColor) {
        String basic = TextNormalizer.basic(rawColor);
        if (basic == null) {
            return null;
        }
        String found = findIn(basic);
        return found != null ? found : basic;
    }

    public static String family(String canonicalColor) {
        if (canonicalColor == null) {
            return null;
        }
        String last = canonicalColor.contains(" ")
                ? canonicalColor.substring(canonicalColor.lastIndexOf(' ') + 1)
                : canonicalColor;
        return FAMILY_OVERRIDES.getOrDefault(last, last);
    }
}
