package io.catalogsentinel.api.web;

import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.ingestion.ProcessedEventRecord;
import io.catalogsentinel.core.listing.ListingQueryService;
import io.catalogsentinel.core.listing.ListingRecord;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/listings")
@Tag(name = "Listings", description = "Catalog explorer")
public class ListingController {

    private final ListingQueryService listings;
    private final SourceRepository sources;

    public ListingController(ListingQueryService listings, SourceRepository sources) {
        this.listings = listings;
        this.sources = sources;
    }

    @GetMapping
    @Operation(summary = "Search listings",
            description = "Filters: sourceId or sourceCode, status, brand, category, gtin, q (title text), linked "
                    + "(has a canonical product). Inputs are normalized like ingested data. Newest first, keyset pagination.")
    public PageResponse<ListingRecord> search(@RequestParam(required = false) Long sourceId,
                                              @RequestParam(required = false) String sourceCode,
                                              @RequestParam(required = false) ListingStatus status,
                                              @RequestParam(required = false) String brand,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String gtin,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) Boolean linked,
                                              @RequestParam(required = false) String cursor,
                                              @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        Long effectiveSourceId = sourceId;
        if (effectiveSourceId == null && sourceCode != null && !sourceCode.isBlank()) {
            var source = sources.findByCode(sourceCode.strip());
            if (source.isEmpty()) {
                return PageResponse.of(new Page<>(List.of(), null), page.limit());
            }
            effectiveSourceId = source.get().sourceId();
        }
        return PageResponse.of(listings.search(effectiveSourceId, status, brand, category, gtin, q, linked, page),
                page.limit());
    }

    @GetMapping("/{listingId}")
    @Operation(summary = "Get a listing with its recent event history")
    public ListingDetail get(@PathVariable long listingId) {
        return new ListingDetail(listings.get(listingId), listings.history(listingId, 20));
    }

    public record ListingDetail(ListingRecord listing, List<ProcessedEventRecord> history) {
    }
}
