package io.catalogsentinel.domain.normalize;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonicalizes brand names: folds case and punctuation, drops corporate suffixes and generic trailing
 * words ("Samsung Electronics Co., Ltd." becomes {@code samsung}) and applies a small alias table for
 * names whose canonical form cannot be derived mechanically ("Hewlett-Packard" becomes {@code hp}).
 */
public final class BrandNormalizer {

    private static final Set<String> CORPORATE_SUFFIXES = Set.of(
            "inc", "incorporated", "corp", "corporation", "co", "company", "ltd", "limited", "llc",
            "gmbh", "ag", "plc", "sa", "srl", "bv", "kk", "pty", "oy", "ab", "spa", "nv");
    private static final Set<String> GENERIC_TRAILING_WORDS = Set.of(
            "electronics", "international", "group", "brands", "technologies", "technology", "usa",
            "america", "global", "products", "industries", "holdings", "official");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("hewlett packard", "hp"),
            Map.entry("hewlett packard enterprise", "hpe"),
            Map.entry("apple computer", "apple"),
            Map.entry("procter and gamble", "procter gamble"),
            Map.entry("p and g", "procter gamble"),
            Map.entry("wd", "western digital"),
            Map.entry("lg electronic", "lg"),
            Map.entry("bose audio", "bose"),
            Map.entry("dewalt tools", "dewalt"),
            Map.entry("black and decker", "black decker"),
            Map.entry("black plus decker", "black decker"),
            Map.entry("kitchen aid", "kitchenaid"),
            Map.entry("lego group", "lego"),
            Map.entry("the lego group", "lego"));

    private BrandNormalizer() {
    }

    public static String normalize(String brand) {
        // Collapse dotted abbreviations first ("S.A." -> "SA", "Co." -> "Co") so they are recognized as suffixes.
        String s = TextNormalizer.basic(brand == null ? null : brand.replace(".", ""));
        if (s == null) {
            return null;
        }
        String aliased = ALIASES.get(s);
        if (aliased != null) {
            return aliased;
        }
        List<String> tokens = new java.util.ArrayList<>(Arrays.asList(s.split(" ")));
        if (tokens.size() > 1 && tokens.get(0).equals("the")) {
            tokens.remove(0);
        }
        boolean changed = true;
        while (changed && tokens.size() > 1) {
            changed = false;
            String last = tokens.get(tokens.size() - 1);
            if (CORPORATE_SUFFIXES.contains(last) || GENERIC_TRAILING_WORDS.contains(last)) {
                tokens.remove(tokens.size() - 1);
                changed = true;
            }
        }
        String result = String.join(" ", tokens);
        return ALIASES.getOrDefault(result, result);
    }
}
