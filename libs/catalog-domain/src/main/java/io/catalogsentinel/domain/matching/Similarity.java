package io.catalogsentinel.domain.matching;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** String similarity primitives used by the matchers. All functions return values in [0, 1]. */
public final class Similarity {

    private Similarity() {
    }

    /**
     * Trigram set compatible with PostgreSQL's pg_trgm: each alphanumeric word is padded with two leading
     * spaces and one trailing space before 3-grams are taken. Keeping the in-memory and in-database
     * definitions identical means candidate retrieval (database) and scoring (Java) agree.
     */
    public static Set<String> trigrams(String normalized) {
        Set<String> grams = new LinkedHashSet<>();
        if (normalized == null) {
            return grams;
        }
        for (String word : normalized.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.isEmpty()) {
                continue;
            }
            String padded = "  " + word + " ";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                grams.add(padded.substring(i, i + 3));
            }
        }
        return grams;
    }

    /** pg_trgm {@code similarity()}: shared trigrams over the union of trigrams. */
    public static double trigramSimilarity(Set<String> a, Set<String> b) {
        return jaccard(a, b);
    }

    public static <T> double jaccard(Set<T> a, Set<T> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        Set<T> smaller = a.size() <= b.size() ? a : b;
        Set<T> larger = smaller == a ? b : a;
        for (T t : smaller) {
            if (larger.contains(t)) {
                intersection++;
            }
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /** Overlap coefficient: shared elements over the size of the smaller set (robust to extra words). */
    public static <T> double overlap(Set<T> a, Set<T> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<T> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / Math.min(a.size(), b.size());
    }

    /** Jaro-Winkler similarity with the standard prefix scale of 0.1 over at most 4 characters. */
    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int matchDistance = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] matched1 = new boolean[len1];
        boolean[] matched2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (!matched2[j] && s1.charAt(i) == s2.charAt(j)) {
                    matched1[i] = true;
                    matched2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (matched1[i]) {
                while (!matched2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
