package io.catalogsentinel.core.listing;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.listing.ListingStatus;
import io.catalogsentinel.domain.source.SourceRole;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Read-side listing queries for the catalog explorer and listing detail pages. */
@Repository
public class ListingQueryRepository {

    static final String SELECT = """
            SELECT l.listing_id, l.source_id, s.code AS source_code, s.role AS source_role, l.external_product_id,
                   l.status, l.source_version, l.title, l.brand, l.model, l.category, l.gtin,
                   l.attributes::text AS attributes, l.normalized_title, l.normalized_brand, l.normalized_model,
                   l.category_leaf, l.extracted_attributes::text AS extracted_attributes, l.content_hash,
                   l.last_event_id, l.source_updated_at, l.withdrawn_at, l.created_at, l.updated_at,
                   k.canonical_product_id, k.confidence AS link_confidence, k.match_method AS link_method
            FROM product_listings l
            JOIN catalog_sources s ON s.source_id = l.source_id
            LEFT JOIN listing_product_links k ON k.listing_id = l.listing_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ListingRecord> mapper;

    public ListingQueryRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.mapper = (rs, i) -> new ListingRecord(
                rs.getLong("listing_id"),
                rs.getLong("source_id"),
                rs.getString("source_code"),
                SourceRole.valueOf(rs.getString("source_role")),
                rs.getString("external_product_id"),
                ListingStatus.valueOf(rs.getString("status")),
                rs.getLong("source_version"),
                rs.getString("title"),
                rs.getString("brand"),
                rs.getString("model"),
                rs.getString("category"),
                rs.getString("gtin"),
                json.readStringMap(rs.getString("attributes")),
                rs.getString("normalized_title"),
                rs.getString("normalized_brand"),
                rs.getString("normalized_model"),
                rs.getString("category_leaf"),
                json.readStringMap(rs.getString("extracted_attributes")),
                rs.getString("content_hash"),
                JdbcValues.uuid(rs, "last_event_id"),
                JdbcValues.instant(rs, "source_updated_at"),
                JdbcValues.instant(rs, "withdrawn_at"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                JdbcValues.nullableLong(rs, "canonical_product_id"),
                JdbcValues.nullableDouble(rs, "link_confidence"),
                rs.getString("link_method"));
    }

    public RowMapper<ListingRecord> mapper() {
        return mapper;
    }

    public Optional<ListingRecord> findById(long listingId) {
        return jdbc.query(SELECT + " WHERE l.listing_id = :id", new MapSqlParameterSource("id", listingId), mapper)
                .stream().findFirst();
    }

    public List<ListingRecord> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(SELECT + " WHERE l.listing_id IN (:ids) ORDER BY l.listing_id",
                new MapSqlParameterSource("ids", ids), mapper);
    }

    public Optional<ListingRecord> findBySourceAndExternalId(long sourceId, String externalProductId) {
        return jdbc.query(SELECT + " WHERE l.source_id = :sourceId AND l.external_product_id = :externalId",
                new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("externalId", externalProductId),
                mapper).stream().findFirst();
    }

    /**
     * Filtered, keyset-paginated search ordered by listing_id descending. Only the filters that are present
     * become predicates, so each combination gets a plan that can use the matching index.
     */
    public List<ListingRecord> search(ListingFilter filter, Long beforeId, int fetchSize) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", fetchSize);
        if (filter.sourceId() != null) {
            sql.append(" AND l.source_id = :sourceId");
            p.addValue("sourceId", filter.sourceId());
        }
        if (filter.status() != null) {
            sql.append(" AND l.status = :status");
            p.addValue("status", filter.status().name());
        }
        if (filter.normalizedBrand() != null) {
            sql.append(" AND l.normalized_brand = :brand");
            p.addValue("brand", filter.normalizedBrand());
        }
        if (filter.categoryLeaf() != null) {
            sql.append(" AND l.category_leaf = :categoryLeaf");
            p.addValue("categoryLeaf", filter.categoryLeaf());
        }
        if (filter.gtin() != null) {
            sql.append(" AND l.gtin = :gtin");
            p.addValue("gtin", filter.gtin());
        }
        if (filter.normalizedQuery() != null) {
            // ILIKE '%q%' is served by the trigram GIN index on normalized_title (for ACTIVE rows).
            sql.append(" AND l.normalized_title ILIKE :q");
            p.addValue("q", "%" + escapeLike(filter.normalizedQuery()) + "%");
        }
        if (filter.linked() != null) {
            sql.append(filter.linked() ? " AND k.listing_id IS NOT NULL" : " AND k.listing_id IS NULL");
        }
        if (beforeId != null) {
            sql.append(" AND l.listing_id < :beforeId");
            p.addValue("beforeId", beforeId);
        }
        sql.append(" ORDER BY l.listing_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), p, mapper);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Search filter. String filters are expected in normalized form (the service normalizes user input
     * with the same functions used at ingestion time).
     */
    public record ListingFilter(Long sourceId, ListingStatus status, String normalizedBrand, String categoryLeaf,
                                String gtin, String normalizedQuery, Boolean linked) {
        public static ListingFilter none() {
            return new ListingFilter(null, null, null, null, null, null, null);
        }
    }
}
