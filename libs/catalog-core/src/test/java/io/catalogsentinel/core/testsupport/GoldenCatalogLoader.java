package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.listing.ListingEventType;
import io.catalogsentinel.domain.source.SourceRole;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads a labeled golden TSV into the database through the real ingestion engine. Sources: {@code ref} becomes
 * the reference catalog {@code ref-supplier}; {@code mkt}/{@code mkt2} become marketplaces.
 */
public final class GoldenCatalogLoader {

    public static final Map<String, String> SOURCE_CODES = Map.of("ref", "ref-supplier", "mkt", "acme-market",
            "mkt2", "beta-market");

    private GoldenCatalogLoader() {
    }

    /** @return golden id to {listing id, truth key} */
    public static Map<String, Loaded> load(CoreTestContext ctx, Path tsv) {
        ctx.sourceService.register("ref-supplier", "Reference Supplier", SourceRole.REFERENCE, true);
        ctx.sourceService.register("acme-market", "Acme Marketplace", SourceRole.MARKETPLACE, true);
        ctx.sourceService.register("beta-market", "Beta Marketplace", SourceRole.MARKETPLACE, true);
        Map<String, Loaded> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("id\t") || line.startsWith("#")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                Map<String, String> attrs = new HashMap<>();
                for (String pair : c[9].split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        attrs.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
                IncomingListingEvent event = new IncomingListingEvent(UUID.randomUUID(), ListingEventType.LISTING_UPSERTED,
                        SOURCE_CODES.get(c[1]), c[0], 1, Events.T0,
                        ListingContent.of(c[4], blank(c[5]), blank(c[6]), blank(c[7]), blank(c[8]), attrs));
                long listingId = ctx.ingestionService.ingest(Events.sync(event)).listingId();
                out.put(c[0], new Loaded(listingId, c[2], c[3], c[1]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public static List<Loaded> listings(Map<String, Loaded> loaded) {
        return List.copyOf(loaded.values());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public record Loaded(long listingId, String truthKey, String family, String source) {
    }
}
