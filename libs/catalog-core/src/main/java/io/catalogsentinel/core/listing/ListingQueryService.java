package io.catalogsentinel.core.listing;

import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.ingestion.ProcessedEventRecord;
import io.catalogsentinel.core.ingestion.ProcessedEventRepository;
import io.catalogsentinel.domain.listing.Gtin;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.normalize.BrandNormalizer;
import io.catalogsentinel.domain.normalize.CategoryNormalizer;
import io.catalogsentinel.domain.normalize.TextNormalizer;
import org.springframework.stereotype.Service;

import java.util.List;

/** Catalog explorer queries. User-supplied filters are normalized exactly like ingested data. */
@Service
public class ListingQueryService {

    private final ListingQueryRepository repository;
    private final ProcessedEventRepository processedEvents;

    public ListingQueryService(ListingQueryRepository repository, ProcessedEventRepository processedEvents) {
        this.repository = repository;
        this.processedEvents = processedEvents;
    }

    public Page<ListingRecord> search(Long sourceId, ListingStatus status, String brand, String category,
                                      String gtin, String query, Boolean linked, PageRequest page) {
        String canonicalGtin = gtin == null || gtin.isBlank() ? null : Gtin.parse(gtin).map(Gtin::value).orElse(gtin.strip());
        ListingQueryRepository.ListingFilter filter = new ListingQueryRepository.ListingFilter(
                sourceId,
                status,
                BrandNormalizer.normalize(brand),
                CategoryNormalizer.leaf(CategoryNormalizer.normalize(category)),
                canonicalGtin,
                TextNormalizer.normalizeTitle(query),
                linked);
        Long beforeId = page.cursor() == null ? null : CursorCodec.decodeLong(page.cursor());
        return Page.fromOverfetch(repository.search(filter, beforeId, page.fetchSize()), page,
                l -> CursorCodec.encode(l.listingId()));
    }

    public ListingRecord get(long listingId) {
        return repository.findById(listingId).orElseThrow(() -> new NotFoundException("listing", listingId));
    }

    public List<ProcessedEventRecord> history(long listingId, int limit) {
        return processedEvents.historyForListing(listingId, Math.max(1, Math.min(limit, 100)));
    }
}
