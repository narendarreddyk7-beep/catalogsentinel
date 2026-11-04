package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.CursorCodec;
import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.Page;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.domain.normalize.CategoryNormalizer;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;

/** Gap triage queries: priority-ranked, filterable, keyset-paginated on (priority_score, gap_id). */
@Repository
public class GapQueryRepository {

    private static final String SELECT = """
            SELECT g.gap_id, g.canonical_product_id, g.target_source_id, t.code AS target_code, g.status, g.reason,
                   g.confidence, g.priority_score, g.priority, g.best_candidate_listing_id, g.best_candidate_score,
                   g.evidence::text AS evidence, g.detection_count, g.first_detected_at, g.last_detected_at,
                   g.last_job_id, g.resolved_at, g.resolution, g.updated_at,
                   c.title AS product_title, c.brand AS product_brand, c.category_leaf AS product_category,
                   r.listing_id AS ref_listing_id, r.title AS ref_title, rs.code AS ref_source, r.gtin AS ref_gtin,
                   b.title AS best_title, bs.code AS best_source
            FROM selection_gaps g
            JOIN catalog_sources t ON t.source_id = g.target_source_id
            JOIN canonical_products c ON c.canonical_product_id = g.canonical_product_id
            JOIN product_listings r ON r.listing_id = g.reference_listing_id
            JOIN catalog_sources rs ON rs.source_id = r.source_id
            LEFT JOIN product_listings b ON b.listing_id = g.best_candidate_listing_id
            LEFT JOIN catalog_sources bs ON bs.source_id = b.source_id
            """;

    private static final RowMapper<GapView> MAPPER = (rs, i) -> new GapView(
            rs.getLong("gap_id"), rs.getLong("canonical_product_id"), rs.getLong("target_source_id"),
            rs.getString("target_code"), rs.getString("status"), rs.getString("reason"), rs.getBigDecimal("confidence"),
            rs.getBigDecimal("priority_score"), rs.getString("priority"),
            JdbcValues.nullableLong(rs, "best_candidate_listing_id"), rs.getBigDecimal("best_candidate_score"),
            rs.getString("evidence"), rs.getInt("detection_count"), JdbcValues.instant(rs, "first_detected_at"),
            JdbcValues.instant(rs, "last_detected_at"), JdbcValues.nullableLong(rs, "last_job_id"),
            JdbcValues.instant(rs, "resolved_at"), rs.getString("resolution"), JdbcValues.instant(rs, "updated_at"),
            rs.getString("product_title"), rs.getString("product_brand"), rs.getString("product_category"),
            rs.getLong("ref_listing_id"), rs.getString("ref_title"), rs.getString("ref_source"), rs.getString("ref_gtin"),
            rs.getString("best_title"), rs.getString("best_source"));

    private final NamedParameterJdbcTemplate jdbc;

    public GapQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<GapView> search(Long targetSourceId, String status, String priority, String reason, String category,
                                Double minConfidence, PageRequest page) {
        return search(targetSourceId, status, priority, reason, category, null, minConfidence, page);
    }

    /**
     * @param categoryRoot top-level segment of the reference listing's normalized category (the grouping used by
     *                     the coverage view); {@code uncategorized} selects reference listings without a category
     */
    public Page<GapView> search(Long targetSourceId, String status, String priority, String reason, String category,
                                String categoryRoot, Double minConfidence, PageRequest page) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", page.fetchSize());
        if (targetSourceId != null) {
            sql.append(" AND g.target_source_id = :target");
            p.addValue("target", targetSourceId);
        }
        if (status != null) {
            sql.append(" AND g.status = :status");
            p.addValue("status", status);
        }
        if (priority != null) {
            sql.append(" AND g.priority = :priority");
            p.addValue("priority", priority);
        }
        if (reason != null) {
            sql.append(" AND g.reason = :reason");
            p.addValue("reason", reason);
        }
        String leaf = CategoryNormalizer.leaf(CategoryNormalizer.normalize(category));
        if (leaf != null) {
            sql.append(" AND c.category_leaf = :leaf");
            p.addValue("leaf", leaf);
        }
        if (categoryRoot != null && !categoryRoot.isBlank()) {
            if (categoryRoot.strip().equalsIgnoreCase("uncategorized")) {
                sql.append(" AND coalesce(r.normalized_category, '') = ''");
            } else {
                String normalized = CategoryNormalizer.normalize(categoryRoot);
                sql.append(" AND split_part(r.normalized_category, ' > ', 1) = :root");
                p.addValue("root", normalized == null ? "" : normalized.split(" > ")[0]);
            }
        }
        if (minConfidence != null) {
            sql.append(" AND g.confidence >= :minConfidence");
            p.addValue("minConfidence", JdbcValues.score(minConfidence));
        }
        if (page.cursor() != null) {
            String[] key = CursorCodec.decode(page.cursor(), 2);
            sql.append(" AND (g.priority_score, g.gap_id) < (:cScore, :cId)");
            try {
                p.addValue("cScore", new BigDecimal(key[0])).addValue("cId", Long.parseLong(key[1]));
            } catch (NumberFormatException e) {
                throw new CursorCodec.InvalidCursorException("malformed cursor");
            }
        }
        sql.append(" ORDER BY g.priority_score DESC, g.gap_id DESC LIMIT :limit");
        return Page.fromOverfetch(jdbc.query(sql.toString(), p, MAPPER), page,
                g -> CursorCodec.encode(g.priorityScore().toPlainString(), g.gapId()));
    }

    public GapView get(long gapId) {
        return jdbc.query(SELECT + " WHERE g.gap_id = :id", new MapSqlParameterSource("id", gapId), MAPPER)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("gap", gapId));
    }

    /** {@code evidenceJson} is the stored evidence document, passed through to API responses as JSON. */
    public record GapView(long gapId, long canonicalProductId, long targetSourceId, String targetSourceCode,
                          String status, String reason, BigDecimal confidence, BigDecimal priorityScore,
                          String priority, Long bestCandidateListingId, BigDecimal bestCandidateScore,
                          String evidenceJson, int detectionCount, Instant firstDetectedAt, Instant lastDetectedAt,
                          Long lastJobId, Instant resolvedAt, String resolution, Instant updatedAt,
                          String productTitle, String productBrand, String productCategory,
                          long referenceListingId, String referenceTitle, String referenceSourceCode,
                          String referenceGtin, String bestCandidateTitle, String bestCandidateSourceCode) {
    }
}
