package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.matching.CandidateBlocker;
import io.catalogsentinel.domain.matching.ProductFeatures;
import io.catalogsentinel.domain.normalize.NormalizedListing;
import io.catalogsentinel.domain.normalize.ProductAttributes;
import io.catalogsentinel.domain.source.SourceRole;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Candidate retrieval and feature loading for matching. Retrieval uses three indexed strategies so that a
 * listing is compared with a handful of plausible candidates instead of the whole catalog:
 * <ol>
 *   <li>GTIN equality (partial B-tree on active rows);</li>
 *   <li>normalized brand + model prefix ({@code text_pattern_ops} B-tree), which also finds regional and
 *       packaging suffixes of the same part number;</li>
 *   <li>title trigram similarity ({@code %} operator, GIN {@code gin_trgm_ops}), top-K by similarity.</li>
 * </ol>
 * The in-memory {@link CandidateBlocker} implements the same strategies for offline evaluation.
 */
@Repository
public class MatchingRepository {

    static final String SELECT_MATCHABLE = """
            SELECT l.listing_id, l.source_id, s.role AS source_role, l.status, l.content_hash, l.title, l.brand,
                   l.model, l.category, l.gtin, l.normalized_title, l.normalized_brand, l.normalized_model,
                   l.normalized_category, l.category_leaf, l.extracted_attributes::text AS extracted_attributes
            FROM product_listings l JOIN catalog_sources s ON s.source_id = l.source_id
            """;

    private static final String BY_GTIN_SQL = """
            SELECT listing_id FROM product_listings
            WHERE gtin = :gtin AND status = 'ACTIVE' AND listing_id <> :id
            ORDER BY listing_id LIMIT :limit
            """;

    private static final String BY_BRAND_MODEL_SQL = """
            SELECT listing_id FROM product_listings
            WHERE normalized_brand = :brand AND normalized_model LIKE :modelPrefix
              AND status = 'ACTIVE' AND listing_id <> :id
            ORDER BY listing_id LIMIT :limit
            """;

    /** {@code %} applies pg_trgm.similarity_threshold (default 0.3) and is served by the GIN trigram index. */
    private static final String BY_TITLE_TRIGRAM_SQL = """
            SELECT listing_id, similarity(normalized_title, :title) AS sim
            FROM product_listings
            WHERE normalized_title % :title AND status = 'ACTIVE' AND listing_id <> :id
            ORDER BY sim DESC, listing_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<MatchableListing> mapper;

    public MatchingRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> {
            Map<String, String> extracted = json.readStringMap(rs.getString("extracted_attributes"));
            String normalizedTitle = rs.getString("normalized_title");
            NormalizedListing n = new NormalizedListing(normalizedTitle, rs.getString("normalized_brand"),
                    rs.getString("normalized_model"), rs.getString("normalized_category"), rs.getString("category_leaf"),
                    toAttributes(extracted));
            long id = rs.getLong("listing_id");
            String title = rs.getString("title");
            String gtin = rs.getString("gtin");
            return new MatchableListing(id, rs.getLong("source_id"), SourceRole.valueOf(rs.getString("source_role")),
                    ListingStatus.valueOf(rs.getString("status")), rs.getString("content_hash"), title,
                    rs.getString("brand"), rs.getString("model"), rs.getString("category"), gtin,
                    rs.getString("extracted_attributes"),
                    normalizedTitle == null ? null : ProductFeatures.of(Long.toString(id), title, gtin, n));
        };
    }

    public Optional<MatchableListing> find(long listingId) {
        return jdbc.query(SELECT_MATCHABLE + " WHERE l.listing_id = :id", new MapSqlParameterSource("id", listingId), mapper)
                .stream().findFirst();
    }

    public List<MatchableListing> findAll(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(SELECT_MATCHABLE + " WHERE l.listing_id IN (:ids) ORDER BY l.listing_id",
                new MapSqlParameterSource("ids", listingIds), mapper);
    }

    /**
     * Candidate listing ids with the strongest retrieval method that found each; ordered by method strength
     * (GTIN, brand+model, trigram) and then by retrieval order.
     */
    public Map<Long, String> retrieveCandidates(MatchableListing listing, int trigramTopK) {
        Map<Long, String> found = new LinkedHashMap<>();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", listing.listingId()).addValue("limit", 50);
        if (listing.gtin() != null) {
            jdbc.query(BY_GTIN_SQL, p.addValue("gtin", listing.gtin()), (rs, i) -> rs.getLong(1))
                    .forEach(id -> found.putIfAbsent(id, "GTIN"));
        }
        ProductFeatures f = listing.features();
        if (f.brand() != null && f.model() != null) {
            String prefix = CandidateBlocker.modelPrefix(f.model()) + "%";
            jdbc.query(BY_BRAND_MODEL_SQL, new MapSqlParameterSource().addValue("id", listing.listingId())
                            .addValue("limit", 50).addValue("brand", f.brand()).addValue("modelPrefix", prefix),
                    (rs, i) -> rs.getLong(1)).forEach(id -> found.putIfAbsent(id, "BRAND_MODEL"));
        }
        if (f.normalizedTitle() != null && !f.normalizedTitle().isBlank()) {
            jdbc.query(BY_TITLE_TRIGRAM_SQL, new MapSqlParameterSource().addValue("id", listing.listingId())
                            .addValue("limit", trigramTopK).addValue("title", f.normalizedTitle()),
                    (rs, i) -> rs.getLong(1)).forEach(id -> found.putIfAbsent(id, "TITLE_TRIGRAM"));
        }
        return found;
    }

    /** Serializes matching of one listing across workers without blocking ingestion of that listing. */
    public void lockListingForMatching(long listingId) {
        jdbc.query("SELECT pg_advisory_xact_lock(:id)", new MapSqlParameterSource("id", listingId), (rs, i) -> 1);
    }

    /** Active listing ids, in id order, after {@code afterId} (keyset scan for bulk re-matching). */
    public List<Long> activeListingIds(Long sourceId, long afterId, int limit, boolean onlyUnlinkedOrStale) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.listing_id FROM product_listings l
                LEFT JOIN listing_product_links k ON k.listing_id = l.listing_id
                WHERE l.status = 'ACTIVE' AND l.listing_id > :after""");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("after", afterId).addValue("limit", limit);
        if (sourceId != null) {
            sql.append(" AND l.source_id = :sourceId");
            p.addValue("sourceId", sourceId);
        }
        if (onlyUnlinkedOrStale) {
            sql.append(" AND (k.listing_id IS NULL OR k.linked_content_hash IS DISTINCT FROM l.content_hash)");
        }
        sql.append(" ORDER BY l.listing_id LIMIT :limit");
        return jdbc.query(sql.toString(), p, (rs, i) -> rs.getLong(1));
    }

    static ProductAttributes toAttributes(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return ProductAttributes.EMPTY;
        }
        return new ProductAttributes(
                intOrNull(m.get("storage_gb")), intOrNull(m.get("ram_gb")), m.get("color"),
                m.get("screen_in") == null ? null : Double.valueOf(m.get("screen_in")), m.get("size"),
                intOrNull(m.get("generation")), intOrNull(m.get("pack_count")), intOrNull(m.get("volume_ml")),
                intOrNull(m.get("capacity_mah")), intOrNull(m.get("wattage_w")), doubleOrNull(m.get("voltage_v")),
                doubleOrNull(m.get("ounces")));
    }

    private static Integer intOrNull(String s) {
        return s == null ? null : Integer.valueOf(s);
    }

    private static Double doubleOrNull(String s) {
        return s == null ? null : Double.valueOf(s);
    }
}
