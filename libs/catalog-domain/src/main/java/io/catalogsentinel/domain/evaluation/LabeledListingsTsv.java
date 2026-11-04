package io.catalogsentinel.domain.evaluation;

import io.catalogsentinel.domain.matching.ProductFeatures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads labeled listings from TSV with header
 * {@code id source truth_key family title brand model category gtin attributes}; attributes are
 * {@code key=value} pairs separated by {@code ;}. Empty cells are {@code null}. GTINs are canonicalized
 * the same way ingestion does.
 */
public final class LabeledListingsTsv {

    private LabeledListingsTsv() {
    }

    public static List<LabeledListing> read(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<LabeledListing> out = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                if (c.length < 10) {
                    throw new IllegalArgumentException("expected 10 columns: " + line);
                }
                String gtin = blank(c[8]) == null ? null
                        : io.catalogsentinel.domain.listing.Gtin.parse(c[8]).map(g -> g.value()).orElse(null);
                ProductFeatures f = ProductFeatures.fromRaw(c[0], c[4], blank(c[5]), blank(c[6]), blank(c[7]), gtin,
                        parseAttributes(c[9]));
                out.add(new LabeledListing(c[0], c[1], c[2], c[3], f));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> parseAttributes(String cell) {
        Map<String, String> m = new LinkedHashMap<>();
        if (blank(cell) == null) {
            return m;
        }
        for (String pair : cell.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                m.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        return m;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
