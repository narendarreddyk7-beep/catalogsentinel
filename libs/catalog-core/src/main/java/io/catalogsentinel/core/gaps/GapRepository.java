package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.gap.GapStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Write side of selection gaps plus the queries reconciliation needs. */
@Repository
public class GapRepository {

    /**
     * Which reference listings are evidence for gap detection: ACTIVE listings of ENABLED reference sources,
     * restricted to the selected reference source when a run has one ({@code %1$s}). The same predicate drives
     * the product's eligibility, its reference listings, the coverage numerator and the demand signal, so they
     * can never disagree (the denominator is {@link #referenceSourceScope}).
     */
    private static final String ELIGIBLE_REFERENCE = "s.role = 'REFERENCE' AND s.enabled AND l.status = 'ACTIVE' %1$s";

    /**
     * Canonical products with at least one eligible reference listing, with the facts reconciliation needs, in id
     * order for keyset batching.
     */
    private static final String REFERENCE_PRODUCTS_SQL = """
            SELECT c.canonical_product_id,
                   array_agg(DISTINCT l.listing_id ORDER BY l.listing_id) FILTER (WHERE %2$s) AS reference_listings,
                   count(DISTINCT l.source_id) FILTER (WHERE %2$s) AS reference_sources,
                   min(l.listing_id) FILTER (WHERE l.source_id = :target AND l.status = 'ACTIVE') AS covering_listing,
                   max(CASE WHEN %2$s AND (l.attributes ->> 'synthetic_demand_index') ~ '^[0-9]*\\.?[0-9]+$'
                            THEN (l.attributes ->> 'synthetic_demand_index')::numeric END) AS synthetic_demand
            FROM canonical_products c
            JOIN listing_product_links k ON k.canonical_product_id = c.canonical_product_id
            JOIN product_listings l ON l.listing_id = k.listing_id
            JOIN catalog_sources s ON s.source_id = l.source_id
            WHERE c.status = 'ACTIVE' AND c.canonical_product_id > :after
            GROUP BY c.canonical_product_id
            HAVING count(*) FILTER (WHERE %2$s) > 0
            ORDER BY c.canonical_product_id
            LIMIT :limit
            """;

    /**
     * The closest target listing to any of the product's reference listings, by score. Only current candidates
     * count as evidence: both listings ACTIVE and unchanged since scoring (a superseded or stale decision says
     * nothing about the listings as they are now; docs/adr/0013-match-evidence-currency.md).
     */
    private static final String BEST_CANDIDATE_SQL = """
            SELECT m.candidate_id, t.listing_id AS target_listing, m.score, m.decision, m.review_status,
                   ARRAY(SELECT DISTINCT e ->> 'code' FROM jsonb_array_elements(m.evidence) e) AS codes
            FROM match_candidates m
            JOIN product_listings la ON la.listing_id = m.left_listing_id
            JOIN product_listings lb ON lb.listing_id = m.right_listing_id
            JOIN product_listings t
              ON t.listing_id = CASE WHEN m.left_listing_id IN (:refs) THEN m.right_listing_id ELSE m.left_listing_id END
            WHERE (m.left_listing_id IN (:refs) OR m.right_listing_id IN (:refs))
              AND t.source_id = :target AND t.status = 'ACTIVE'
              AND m.review_status <> 'SUPERSEDED'
              AND la.status = 'ACTIVE' AND lb.status = 'ACTIVE'
              AND la.content_hash = m.left_content_hash AND lb.content_hash = m.right_content_hash
            ORDER BY m.score DESC, m.candidate_id
            LIMIT 1
            """;

    private static final String UPSERT_GAP_SQL = """
            INSERT INTO selection_gaps (canonical_product_id, target_source_id, reference_listing_id, status, reason,
                                        confidence, priority_score, priority, best_candidate_listing_id,
                                        best_candidate_score, evidence, last_job_id)
            VALUES (:product, :target, :reference, 'OPEN', :reason, :confidence, :priorityScore, :priority,
                    :bestListing, :bestScore, CAST(:evidence AS jsonb), :jobId)
            ON CONFLICT (canonical_product_id, target_source_id) WHERE status IN ('OPEN', 'CONFIRMED', 'DISMISSED')
            DO UPDATE SET
                reference_listing_id = EXCLUDED.reference_listing_id,
                reason = EXCLUDED.reason,
                confidence = EXCLUDED.confidence,
                priority_score = EXCLUDED.priority_score,
                priority = EXCLUDED.priority,
                best_candidate_listing_id = EXCLUDED.best_candidate_listing_id,
                best_candidate_score = EXCLUDED.best_candidate_score,
                evidence = EXCLUDED.evidence,
                detection_count = selection_gaps.detection_count + 1,
                last_detected_at = now(),
                last_job_id = EXCLUDED.last_job_id
            RETURNING gap_id, status, (xmax = 0) AS inserted
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public GapRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<ReferenceProduct> referenceProducts(long targetSourceId, Long referenceSourceId, long afterId, int limit) {
        String eligible = ELIGIBLE_REFERENCE.formatted(referenceSourceId == null ? "" : "AND l.source_id = :refSource");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("target", targetSourceId)
                .addValue("after", afterId).addValue("limit", limit);
        if (referenceSourceId != null) {
            p.addValue("refSource", referenceSourceId);
        }
        return jdbc.query(REFERENCE_PRODUCTS_SQL.formatted("", eligible), p, (rs, i) -> {
            Long[] refs = (Long[]) rs.getArray("reference_listings").getArray();
            BigDecimal demand = rs.getBigDecimal("synthetic_demand");
            return new ReferenceProduct(rs.getLong("canonical_product_id"), Arrays.asList(refs),
                    rs.getInt("reference_sources"), JdbcValues.nullableLong(rs, "covering_listing"),
                    demand == null ? null : demand.doubleValue());
        });
    }

    public Optional<BestCandidateRow> bestCandidate(Collection<Long> referenceListingIds, long targetSourceId) {
        return jdbc.query(BEST_CANDIDATE_SQL, new MapSqlParameterSource().addValue("refs", referenceListingIds)
                .addValue("target", targetSourceId), (rs, i) -> {
            String[] codes = (String[]) rs.getArray("codes").getArray();
            return new BestCandidateRow(rs.getLong("candidate_id"), rs.getLong("target_listing"),
                    rs.getBigDecimal("score").doubleValue(), rs.getString("decision"), rs.getString("review_status"),
                    new HashSet<>(Arrays.asList(codes)));
        }).stream().findFirst();
    }

    /**
     * The coverage denominator: how many reference sources are in scope. All enabled reference sources, or just the
     * selected one (0 if it has been disabled since the run was requested).
     */
    public int referenceSourceScope(Long referenceSourceId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM catalog_sources
                WHERE role = 'REFERENCE' AND enabled AND (CAST(:ref AS bigint) IS NULL OR source_id = :ref)
                """, new MapSqlParameterSource().addValue("ref", referenceSourceId, Types.BIGINT), Integer.class);
        return n == null ? 0 : n;
    }

    public UpsertResult upsert(long productId, long targetSourceId, long referenceListingId, String reason,
                               double confidence, double priorityScore, String priority, Long bestListingId,
                               Double bestScore, Map<String, Object> evidence, long jobId) {
        return jdbc.queryForObject(UPSERT_GAP_SQL, new MapSqlParameterSource()
                        .addValue("product", productId)
                        .addValue("target", targetSourceId)
                        .addValue("reference", referenceListingId)
                        .addValue("reason", reason)
                        .addValue("confidence", JdbcValues.score(confidence))
                        .addValue("priorityScore", BigDecimal.valueOf(priorityScore).setScale(2, java.math.RoundingMode.HALF_UP))
                        .addValue("priority", priority)
                        .addValue("bestListing", bestListingId, Types.BIGINT)
                        .addValue("bestScore", bestScore == null ? null : JdbcValues.score(bestScore), Types.NUMERIC)
                        .addValue("evidence", json.write(evidence))
                        .addValue("jobId", jobId),
                (rs, i) -> new UpsertResult(rs.getLong("gap_id"), GapStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("inserted")));
    }

    /**
     * Live gaps (OPEN, CONFIRMED, DISMISSED) of the target whose product has been merged into another one, with the
     * product the merge chain ends at. Human-decided gaps come first, then the most recently updated, so that when
     * several merged products compete for one survivor the reviewed gap is the one that follows the merge
     * (docs/adr/0014-gaps-follow-product-merges.md).
     */
    public List<MergedProductGap> liveGapsOnMergedProducts(long targetSourceId) {
        return jdbc.query("""
                WITH RECURSIVE chain AS (
                    SELECT g.gap_id, g.status, g.updated_at, g.canonical_product_id AS merged_product,
                           c.merged_into_id AS product, 1 AS depth
                    FROM selection_gaps g
                    JOIN canonical_products c ON c.canonical_product_id = g.canonical_product_id
                    WHERE g.target_source_id = :target AND g.status IN ('OPEN', 'CONFIRMED', 'DISMISSED')
                      AND c.status = 'MERGED' AND c.merged_into_id IS NOT NULL
                    UNION ALL
                    SELECT ch.gap_id, ch.status, ch.updated_at, ch.merged_product, c.merged_into_id, ch.depth + 1
                    FROM chain ch
                    JOIN canonical_products c ON c.canonical_product_id = ch.product
                    WHERE c.status = 'MERGED' AND c.merged_into_id IS NOT NULL AND ch.depth < 64
                )
                SELECT gap_id, status, merged_product, product AS survivor FROM (
                    SELECT DISTINCT ON (gap_id) gap_id, status, updated_at, merged_product, product
                    FROM chain ORDER BY gap_id, depth DESC
                ) last
                ORDER BY (status <> 'OPEN') DESC, updated_at DESC, gap_id
                """, new MapSqlParameterSource("target", targetSourceId),
                (rs, i) -> new MergedProductGap(rs.getLong("gap_id"), GapStatus.valueOf(rs.getString("status")),
                        rs.getLong("merged_product"), rs.getLong("survivor")));
    }

    /** The live gap (OPEN, CONFIRMED or DISMISSED) of a product for a target, if any. */
    public Optional<GapRow> findLiveGap(long productId, long targetSourceId) {
        return jdbc.query("""
                SELECT gap_id, status FROM selection_gaps
                WHERE canonical_product_id = :p AND target_source_id = :t AND status IN ('OPEN', 'CONFIRMED', 'DISMISSED')
                """, new MapSqlParameterSource().addValue("p", productId).addValue("t", targetSourceId),
                (rs, i) -> new GapRow(rs.getLong("gap_id"), GapStatus.valueOf(rs.getString("status"))))
                .stream().findFirst();
    }

    /** Moves a gap, with its status and review history, to the product its own product was merged into. */
    public void moveToProduct(long gapId, long productId) {
        jdbc.update("UPDATE selection_gaps SET canonical_product_id = :p WHERE gap_id = :id",
                new MapSqlParameterSource().addValue("p", productId).addValue("id", gapId));
    }

    /** Open/confirmed gap for the pair, if any (dismissed gaps are left alone by automation). */
    public Optional<Long> findResolvableGap(long productId, long targetSourceId) {
        return jdbc.query("""
                SELECT gap_id FROM selection_gaps
                WHERE canonical_product_id = :p AND target_source_id = :t AND status IN ('OPEN', 'CONFIRMED')
                """, new MapSqlParameterSource().addValue("p", productId).addValue("t", targetSourceId),
                (rs, i) -> rs.getLong(1)).stream().findFirst();
    }

    /**
     * Open or confirmed gaps of a target whose product no longer qualifies anywhere, with the resolution to
     * apply: merged into another product ({@code MERGED_PRODUCT}); listed only by disabled reference sources
     * ({@code REFERENCE_SOURCE_DISABLED}); or no active reference listing left ({@code REFERENCE_WITHDRAWN}).
     * Eligibility here is global (all enabled reference sources), so a run scoped to one reference source never
     * resolves gaps that other enabled sources still justify.
     */
    public List<Map.Entry<Long, String>> invalidatedGaps(long targetSourceId) {
        return jdbc.query("""
                SELECT gap_id, resolution FROM (
                    SELECT g.gap_id,
                           CASE WHEN c.status = 'MERGED' THEN 'MERGED_PRODUCT'
                                WHEN NOT eligible.any_listing THEN 'REFERENCE_WITHDRAWN'
                                WHEN NOT eligible.enabled_listing THEN 'REFERENCE_SOURCE_DISABLED'
                           END AS resolution
                    FROM selection_gaps g
                    JOIN canonical_products c ON c.canonical_product_id = g.canonical_product_id
                    CROSS JOIN LATERAL (
                        SELECT coalesce(bool_or(true), false) AS any_listing,
                               coalesce(bool_or(s.enabled), false) AS enabled_listing
                        FROM listing_product_links k
                        JOIN product_listings l ON l.listing_id = k.listing_id
                        JOIN catalog_sources s ON s.source_id = l.source_id
                        WHERE k.canonical_product_id = g.canonical_product_id
                          AND l.status = 'ACTIVE' AND s.role = 'REFERENCE'
                    ) eligible
                    WHERE g.target_source_id = :t AND g.status IN ('OPEN', 'CONFIRMED')
                ) x
                WHERE resolution IS NOT NULL
                ORDER BY gap_id
                """, new MapSqlParameterSource("t", targetSourceId),
                (rs, i) -> Map.entry(rs.getLong("gap_id"), rs.getString("resolution")));
    }

    public Optional<GapRow> lock(long gapId) {
        return jdbc.query("SELECT gap_id, status FROM selection_gaps WHERE gap_id = :id FOR UPDATE",
                new MapSqlParameterSource("id", gapId),
                (rs, i) -> new GapRow(rs.getLong("gap_id"), GapStatus.valueOf(rs.getString("status"))))
                .stream().findFirst();
    }

    public void updateStatus(long gapId, GapStatus status, String resolution, Instant now) {
        jdbc.update("""
                UPDATE selection_gaps SET status = :status,
                       resolved_at = CASE WHEN :status = 'RESOLVED' THEN :now ELSE NULL END,
                       resolution = CASE WHEN :status = 'RESOLVED' THEN :resolution ELSE NULL END
                WHERE gap_id = :id
                """, new MapSqlParameterSource().addValue("id", gapId).addValue("status", status.name())
                .addValue("now", JdbcValues.ts(now)).addValue("resolution", resolution, Types.VARCHAR));
    }

    /** A live gap on a merged-away product and the active product its merge chain ends at. */
    public record MergedProductGap(long gapId, GapStatus status, long mergedProductId, long survivorProductId) {
    }

    public record ReferenceProduct(long canonicalProductId, List<Long> referenceListingIds, int referenceSources,
                                   Long coveringListingId, Double syntheticDemand) {
    }

    public record BestCandidateRow(long candidateId, long targetListingId, double score, String decision,
                                   String reviewStatus, Set<String> evidenceCodes) {
    }

    public record UpsertResult(long gapId, GapStatus status, boolean inserted) {
    }

    public record GapRow(long gapId, GapStatus status) {
    }
}
