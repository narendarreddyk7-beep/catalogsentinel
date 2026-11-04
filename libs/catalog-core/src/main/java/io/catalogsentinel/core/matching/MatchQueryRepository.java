package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.normalize.BrandNormalizer;
import io.catalogsentinel.domain.normalize.CategoryNormalizer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Read models for the match review queue and canonical product pages. */
@Repository
public class MatchQueryRepository {

    private static final String CANDIDATE_SELECT = """
            SELECT m.candidate_id, m.score, m.decision, m.review_status, m.matcher_version, m.retrieval_method,
                   m.feature_scores::text AS feature_scores, m.evidence::text AS evidence, m.reviewed_by, m.reviewed_at,
                   m.created_at, m.updated_at,
                   l.listing_id AS l_id, ls.code AS l_source, ls.role AS l_role, l.external_product_id AS l_ext,
                   l.title AS l_title, l.brand AS l_brand, l.model AS l_model, l.gtin AS l_gtin, l.category AS l_category,
                   l.status AS l_status, l.attributes::text AS l_attributes,
                   r.listing_id AS r_id, rs.code AS r_source, rs.role AS r_role, r.external_product_id AS r_ext,
                   r.title AS r_title, r.brand AS r_brand, r.model AS r_model, r.gtin AS r_gtin, r.category AS r_category,
                   r.status AS r_status, r.attributes::text AS r_attributes
            FROM match_candidates m
            JOIN product_listings l ON l.listing_id = m.left_listing_id
            JOIN catalog_sources ls ON ls.source_id = l.source_id
            JOIN product_listings r ON r.listing_id = m.right_listing_id
            JOIN catalog_sources rs ON rs.source_id = r.source_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<CandidateView> candidateMapper;

    public MatchQueryRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.candidateMapper = (rs, i) -> new CandidateView(
                rs.getLong("candidate_id"),
                rs.getBigDecimal("score"),
                rs.getString("decision"),
                rs.getString("review_status"),
                rs.getString("matcher_version"),
                rs.getString("retrieval_method"),
                rs.getString("feature_scores"),
                rs.getString("evidence"),
                rs.getString("reviewed_by"),
                JdbcValues.instant(rs, "reviewed_at"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                side(rs, "l_", json),
                side(rs, "r_", json));
    }

    private static ListingSide side(java.sql.ResultSet rs, String p, JsonCodec json) throws java.sql.SQLException {
        return new ListingSide(rs.getLong(p + "id"), rs.getString(p + "source"), rs.getString(p + "role"),
                rs.getString(p + "ext"), rs.getString(p + "title"), rs.getString(p + "brand"), rs.getString(p + "model"),
                rs.getString(p + "gtin"), rs.getString(p + "category"), rs.getString(p + "status"),
                json.readStringMap(rs.getString(p + "attributes")));
    }

    /** Review queue: highest score first; keyset pagination on (score, candidate_id). */
    public Page<CandidateView> searchCandidates(String reviewStatus, String decision, Double minScore, Long sourceId,
                                                Long listingId, PageRequest page) {
        StringBuilder sql = new StringBuilder(CANDIDATE_SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", page.fetchSize());
        if (reviewStatus != null) {
            sql.append(" AND m.review_status = :reviewStatus");
            p.addValue("reviewStatus", reviewStatus);
        }
        if (decision != null) {
            sql.append(" AND m.decision = :decision");
            p.addValue("decision", decision);
        }
        if (minScore != null) {
            sql.append(" AND m.score >= :minScore");
            p.addValue("minScore", JdbcValues.score(minScore));
        }
        if (sourceId != null) {
            sql.append(" AND (l.source_id = :sourceId OR r.source_id = :sourceId)");
            p.addValue("sourceId", sourceId);
        }
        if (listingId != null) {
            sql.append(" AND (m.left_listing_id = :listingId OR m.right_listing_id = :listingId)");
            p.addValue("listingId", listingId);
        }
        if (page.cursor() != null) {
            String[] key = CursorCodec.decode(page.cursor(), 2);
            sql.append(" AND (m.score, m.candidate_id) < (:cScore, :cId)");
            try {
                p.addValue("cScore", new BigDecimal(key[0])).addValue("cId", Long.parseLong(key[1]));
            } catch (NumberFormatException e) {
                throw new CursorCodec.InvalidCursorException("malformed cursor");
            }
        }
        sql.append(" ORDER BY m.score DESC, m.candidate_id DESC LIMIT :limit");
        return Page.fromOverfetch(jdbc.query(sql.toString(), p, candidateMapper), page,
                c -> CursorCodec.encode(c.score().toPlainString(), c.candidateId()));
    }

    public CandidateView getCandidate(long candidateId) {
        return jdbc.query(CANDIDATE_SELECT + " WHERE m.candidate_id = :id", new MapSqlParameterSource("id", candidateId),
                candidateMapper).stream().findFirst().orElseThrow(() -> new NotFoundException("match candidate", candidateId));
    }

    private static final String PRODUCT_SELECT = """
            SELECT c.canonical_product_id, c.title, c.brand, c.model, c.category, c.gtin, c.category_leaf, c.status,
                   c.merged_into_id, c.representative_listing_id, c.created_at, c.updated_at,
                   (SELECT count(*) FROM listing_product_links k JOIN product_listings l ON l.listing_id = k.listing_id
                     WHERE k.canonical_product_id = c.canonical_product_id AND l.status = 'ACTIVE') AS active_listings,
                   (SELECT count(*) FROM listing_product_links k JOIN product_listings l ON l.listing_id = k.listing_id
                     JOIN catalog_sources s ON s.source_id = l.source_id
                     WHERE k.canonical_product_id = c.canonical_product_id AND l.status = 'ACTIVE'
                       AND s.role = 'MARKETPLACE') AS marketplace_listings
            FROM canonical_products c
            """;

    private static final RowMapper<CanonicalProductView> PRODUCT_MAPPER = (rs, i) -> new CanonicalProductView(
            rs.getLong("canonical_product_id"), rs.getString("title"), rs.getString("brand"), rs.getString("model"),
            rs.getString("category"), rs.getString("gtin"), rs.getString("category_leaf"), rs.getString("status"),
            JdbcValues.nullableLong(rs, "merged_into_id"), JdbcValues.nullableLong(rs, "representative_listing_id"),
            rs.getLong("active_listings"), rs.getLong("marketplace_listings"),
            JdbcValues.instant(rs, "created_at"), JdbcValues.instant(rs, "updated_at"));

    public Page<CanonicalProductView> searchProducts(String q, String brand, String category, PageRequest page) {
        StringBuilder sql = new StringBuilder(PRODUCT_SELECT).append(" WHERE c.status = 'ACTIVE'");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", page.fetchSize());
        if (q != null && !q.isBlank()) {
            sql.append(" AND lower(c.title) LIKE :q");
            p.addValue("q", "%" + q.strip().toLowerCase(java.util.Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String nBrand = BrandNormalizer.normalize(brand);
        if (nBrand != null) {
            sql.append(" AND c.normalized_brand = :brand");
            p.addValue("brand", nBrand);
        }
        String leaf = CategoryNormalizer.leaf(CategoryNormalizer.normalize(category));
        if (leaf != null) {
            sql.append(" AND c.category_leaf = :leaf");
            p.addValue("leaf", leaf);
        }
        if (page.cursor() != null) {
            sql.append(" AND c.canonical_product_id < :before");
            p.addValue("before", CursorCodec.decodeLong(page.cursor()));
        }
        sql.append(" ORDER BY c.canonical_product_id DESC LIMIT :limit");
        return Page.fromOverfetch(jdbc.query(sql.toString(), p, PRODUCT_MAPPER), page,
                c -> CursorCodec.encode(c.canonicalProductId()));
    }

    public CanonicalProductView getProduct(long id) {
        return jdbc.query(PRODUCT_SELECT + " WHERE c.canonical_product_id = :id", new MapSqlParameterSource("id", id),
                PRODUCT_MAPPER).stream().findFirst().orElseThrow(() -> new NotFoundException("canonical product", id));
    }

    public List<LinkedListingView> linkedListings(long canonicalProductId) {
        return jdbc.query("""
                SELECT l.listing_id, s.code AS source_code, s.role AS source_role, l.external_product_id, l.title,
                       l.status, k.confidence, k.match_method, k.review_status, k.via_candidate_id
                FROM listing_product_links k
                JOIN product_listings l ON l.listing_id = k.listing_id
                JOIN catalog_sources s ON s.source_id = l.source_id
                WHERE k.canonical_product_id = :id
                ORDER BY s.role DESC, l.listing_id
                """, new MapSqlParameterSource("id", canonicalProductId), (rs, i) -> new LinkedListingView(
                rs.getLong("listing_id"), rs.getString("source_code"), rs.getString("source_role"),
                rs.getString("external_product_id"), rs.getString("title"), rs.getString("status"),
                rs.getBigDecimal("confidence"), rs.getString("match_method"), rs.getString("review_status"),
                JdbcValues.nullableLong(rs, "via_candidate_id")));
    }

    /** Raw JSON fields ({@code featureScores}, {@code evidence}) are passed through to API responses as-is. */
    public record CandidateView(long candidateId, BigDecimal score, String decision, String reviewStatus,
                                String matcherVersion, String retrievalMethod, String featureScoresJson,
                                String evidenceJson, String reviewedBy, Instant reviewedAt, Instant createdAt,
                                Instant updatedAt, ListingSide left, ListingSide right) {
    }

    public record ListingSide(long listingId, String sourceCode, String sourceRole, String externalProductId,
                              String title, String brand, String model, String gtin, String category, String status,
                              Map<String, String> attributes) {
    }

    public record CanonicalProductView(long canonicalProductId, String title, String brand, String model,
                                       String category, String gtin, String categoryLeaf, String status,
                                       Long mergedIntoId, Long representativeListingId, long activeListings,
                                       long marketplaceListings, Instant createdAt, Instant updatedAt) {
    }

    public record LinkedListingView(long listingId, String sourceCode, String sourceRole, String externalProductId,
                                    String title, String status, BigDecimal confidence, String matchMethod,
                                    String reviewStatus, Long viaCandidateId) {
    }
}
