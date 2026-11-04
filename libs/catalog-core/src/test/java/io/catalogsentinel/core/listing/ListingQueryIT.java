package io.catalogsentinel.core.listing;

import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static io.catalogsentinel.core.testsupport.Events.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

class ListingQueryIT {

    private final CoreTestContext ctx = new CoreTestContext();
    private long marketId;

    @BeforeEach
    void setUp() {
        ctx.reset();
        marketId = ctx.sourceService.register("acme-market", "Acme", SourceRole.MARKETPLACE, true).sourceId();
        ctx.sourceService.register("ref-supplier", "Ref", SourceRole.REFERENCE, true);
        ingest("acme-market", "A1", "Sony WH-1000XM5 Headphones", "Sony", "WH-1000XM5", "036000291452");
        ingest("acme-market", "A2", "Bose QuietComfort Ultra Headphones", "Bose", "QC-ULTRA", null);
        ingest("acme-market", "A3", "Sony WF-1000XM5 Earbuds", "Sony Corporation", "WF-1000XM5", null);
        ingest("ref-supplier", "R1", "Sony WH-1000XM5 Wireless (Black)", "SONY", "WH1000XM5", null);
        ctx.ingestionService.ingest(sync(withdraw("acme-market", "A2", 2)));
    }

    private void ingest(String source, String id, String title, String brand, String model, String gtin) {
        ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), source, id, 1, title, brand, model, gtin, Map.of())));
    }

    private Page<ListingRecord> search(Long sourceId, ListingStatus status, String brand, String gtin, String q) {
        return ctx.listingQueryService.search(sourceId, status, brand, null, gtin, q, null, PageRequest.first(50));
    }

    @Test
    void filtersBySourceStatusBrandGtinAndText() {
        assertThat(search(marketId, null, null, null, null).items()).hasSize(3);
        assertThat(search(null, ListingStatus.ACTIVE, null, null, null).items()).hasSize(3);
        assertThat(search(null, null, "Sony Corp.", null, null).items())
                .extracting(ListingRecord::externalProductId).containsExactlyInAnyOrder("A1", "A3", "R1");
        assertThat(search(null, null, null, "0-36000-29145-2", null).items())
                .extracting(ListingRecord::externalProductId).containsExactly("A1");
        assertThat(search(null, null, null, null, "WH-1000XM5").items())
                .extracting(ListingRecord::externalProductId).containsExactlyInAnyOrder("A1", "R1");
        assertThat(search(null, null, null, null, "100%_off").items()).isEmpty();
    }

    @Test
    void paginatesNewestFirstWithoutGapsOrDuplicates() {
        for (int i = 0; i < 23; i++) {
            ingest("acme-market", "P" + i, "Product " + i, "Acme", "M" + i, null);
        }
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Page<ListingRecord> page = ctx.listingQueryService.search(marketId, null, null, null, null, null, null,
                    new PageRequest(cursor, 5));
            page.items().forEach(l -> seen.add(l.listingId()));
            cursor = page.nextCursor();
            pages++;
            if (pages == 1) {
                // Rows created while a client is paging sort before its cursor (newest first). OFFSET paging would
                // shift every later page by three and return duplicates; with a keyset cursor nothing moves.
                for (int i = 0; i < 3; i++) {
                    ingest("acme-market", "NEW" + i, "New product " + i, "Acme", "N" + i, null);
                }
            }
        } while (cursor != null);
        assertThat(pages).isEqualTo(6);
        assertThat(seen).hasSize(26).doesNotHaveDuplicates();
        List<Long> sorted = new ArrayList<>(seen);
        sorted.sort(java.util.Comparator.reverseOrder());
        assertThat(seen).isEqualTo(sorted);
    }
}
