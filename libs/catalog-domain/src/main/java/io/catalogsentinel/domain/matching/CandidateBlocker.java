package io.catalogsentinel.domain.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory candidate generation ("blocking") that mirrors the database retrieval in catalog-core:
 * <ol>
 *   <li>same GTIN;</li>
 *   <li>same brand and model, or same brand and model prefix (catches regional suffixes like {@code ll/a});</li>
 *   <li>top-K titles by trigram similarity at or above {@value #TRIGRAM_THRESHOLD} (pg_trgm's default), found
 *       through an inverted trigram index, which is what a GIN {@code gin_trgm_ops} index does.</li>
 * </ol>
 * Used by the evaluator and batch tooling to measure candidate recall and the reduction in comparisons
 * versus all-pairs matching.
 */
public final class CandidateBlocker {

    public static final double TRIGRAM_THRESHOLD = 0.3;
    private final int topK;
    private final double maxTrigramDocumentFrequency;

    /**
     * @param topK                        trigram neighbours kept per listing
     * @param maxTrigramDocumentFrequency trigrams occurring in more than this fraction of listings are skipped
     *                                    when scanning postings (they carry little signal and dominate cost)
     */
    public CandidateBlocker(int topK, double maxTrigramDocumentFrequency) {
        this.topK = topK;
        this.maxTrigramDocumentFrequency = maxTrigramDocumentFrequency;
    }

    public CandidateBlocker() {
        this(10, 0.10);
    }

    /** Unordered candidate pair with a canonical ordering of ids. */
    public record Pair(String left, String right) {
        public static Pair of(String a, String b) {
            return a.compareTo(b) <= 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }

    public Set<Pair> candidates(List<ProductFeatures> items) {
        Map<String, List<Integer>> byGtin = new HashMap<>();
        Map<String, List<Integer>> byBrandModel = new HashMap<>();
        Map<String, List<Integer>> byBrand = new HashMap<>();
        Map<String, List<Integer>> postings = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ProductFeatures f = items.get(i);
            if (f.gtin() != null) {
                byGtin.computeIfAbsent(f.gtin(), k -> new ArrayList<>()).add(i);
            }
            if (f.brand() != null && f.model() != null) {
                byBrandModel.computeIfAbsent(f.brand() + "|" + f.model(), k -> new ArrayList<>()).add(i);
                byBrand.computeIfAbsent(f.brand(), k -> new ArrayList<>()).add(i);
            }
            for (String g : f.titleTrigrams()) {
                postings.computeIfAbsent(g, k -> new ArrayList<>()).add(i);
            }
        }
        int maxDf = Math.max(50, (int) (maxTrigramDocumentFrequency * items.size()));

        Set<Pair> pairs = new LinkedHashSet<>();
        int[] shared = new int[items.size()];
        List<Integer> touched = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ProductFeatures f = items.get(i);
            if (f.gtin() != null) {
                addAll(pairs, items, i, byGtin.get(f.gtin()));
            }
            if (f.brand() != null && f.model() != null) {
                addAll(pairs, items, i, byBrandModel.get(f.brand() + "|" + f.model()));
                String prefix = modelPrefix(f.model());
                for (int j : byBrand.getOrDefault(f.brand(), List.of())) {
                    if (j != i && items.get(j).model().startsWith(prefix)) {
                        pairs.add(Pair.of(f.id(), items.get(j).id()));
                    }
                }
            }
            // Trigram top-K through the inverted index.
            touched.clear();
            for (String g : f.titleTrigrams()) {
                List<Integer> list = postings.get(g);
                if (list == null || list.size() > maxDf) {
                    continue;
                }
                for (int j : list) {
                    if (j == i) {
                        continue;
                    }
                    if (shared[j]++ == 0) {
                        touched.add(j);
                    }
                }
            }
            List<double[]> scored = new ArrayList<>();
            for (int j : touched) {
                double sim = Similarity.trigramSimilarity(f.titleTrigrams(), items.get(j).titleTrigrams());
                if (sim >= TRIGRAM_THRESHOLD) {
                    scored.add(new double[]{j, sim});
                }
                shared[j] = 0;
            }
            scored.sort(Comparator.comparingDouble((double[] d) -> -d[1]).thenComparingDouble(d -> d[0]));
            for (int k = 0; k < Math.min(topK, scored.size()); k++) {
                pairs.add(Pair.of(f.id(), items.get((int) scored.get(k)[0]).id()));
            }
        }
        return pairs;
    }

    /** Prefix used for model-prefix retrieval: drop up to 3 trailing characters, keep at least 4. */
    public static String modelPrefix(String model) {
        return model.substring(0, Math.min(model.length(), Math.max(4, model.length() - 3)));
    }

    private static void addAll(Set<Pair> pairs, List<ProductFeatures> items, int i, List<Integer> others) {
        if (others == null) {
            return;
        }
        for (int j : others) {
            if (j != i) {
                pairs.add(Pair.of(items.get(i).id(), items.get(j).id()));
            }
        }
    }
}
