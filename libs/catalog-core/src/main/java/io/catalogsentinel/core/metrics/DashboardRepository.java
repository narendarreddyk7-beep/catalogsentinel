package io.catalogsentinel.core.metrics;

import io.catalogsentinel.core.common.JdbcValues;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Aggregations for the overview and system-health pages. Every figure is computed from the platform's own
 * tables at request time; nothing is precomputed or simulated.
 */
@Repository
public class DashboardRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("since", JdbcValues.ts(now.minus(Duration.ofHours(24))));
        Map<String, Long> sources = counts("SELECT role, count(*) FROM catalog_sources GROUP BY role", p);
        Map<String, Long> listings = counts("SELECT status, count(*) FROM product_listings GROUP BY status", p);
        Map<String, Long> candidates = counts("SELECT review_status, count(*) FROM match_candidates GROUP BY review_status", p);
        Map<String, Long> gapsByStatus = counts("SELECT status, count(*) FROM selection_gaps GROUP BY status", p);
        Map<String, Long> openGapsByPriority = counts(
                "SELECT priority, count(*) FROM selection_gaps WHERE status IN ('OPEN', 'CONFIRMED') GROUP BY priority", p);
        Map<String, Long> events24h = counts(
                "SELECT outcome, count(*) FROM processed_events WHERE processed_at >= :since GROUP BY outcome", p);
        Long canonical = jdbc.queryForObject("SELECT count(*) FROM canonical_products WHERE status = 'ACTIVE'", p, Long.class);
        Map<String, Long> outbox = counts("SELECT status, count(*) FROM outbox_events WHERE status <> 'PUBLISHED' GROUP BY status", p);
        Map<String, Object> lastReconciliation = jdbc.query("""
                SELECT j.job_id, t.code, j.status, j.finished_at FROM reconciliation_jobs j
                JOIN catalog_sources t ON t.source_id = j.target_source_id ORDER BY j.job_id DESC LIMIT 1
                """, p, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", rs.getLong(1));
            m.put("targetSourceCode", rs.getString(2));
            m.put("status", rs.getString(3));
            m.put("finishedAt", JdbcValues.instant(rs, "finished_at"));
            return m;
        }).stream().findFirst().orElse(Map.of());
        return new Summary(now, sources, listings, canonical == null ? 0 : canonical, candidates, gapsByStatus,
                openGapsByPriority, events24h, outbox, lastReconciliation);
    }

    /**
     * Per-minute processed-event counts by outcome and end-to-end latency percentiles by ingestion path over the
     * trailing window (BRIN index on processed_at keeps this cheap on a large append-only table).
     */
    public ProcessingMetrics processing(Instant now, Duration window) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("since", JdbcValues.ts(now.minus(window)));
        List<ThroughputPoint> throughput = jdbc.query("""
                SELECT date_trunc('minute', processed_at) AS minute, outcome, count(*) AS events
                FROM processed_events WHERE processed_at >= :since
                GROUP BY 1, 2 ORDER BY 1, 2
                """, p, (rs, i) -> new ThroughputPoint(JdbcValues.instant(rs, "minute"), rs.getString("outcome"),
                rs.getLong("events")));
        List<LatencyStats> latency = jdbc.query("""
                SELECT ingestion_path, count(*) AS events,
                       percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - received_at)) * 1000 AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - received_at)) * 1000 AS p95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM processed_at - received_at)) * 1000 AS p99,
                       max(extract(epoch FROM processed_at - received_at)) * 1000 AS max_ms
                FROM processed_events WHERE processed_at >= :since
                GROUP BY ingestion_path ORDER BY ingestion_path
                """, p, (rs, i) -> new LatencyStats(rs.getString("ingestion_path"), rs.getLong("events"),
                rs.getDouble("p50"), rs.getDouble("p95"), rs.getDouble("p99"), rs.getDouble("max_ms")));
        Map<String, Object> outbox = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM outbox_events WHERE status = 'PENDING') AS pending,
                       (SELECT count(*) FROM outbox_events WHERE status = 'FAILED') AS failed,
                       (SELECT extract(epoch FROM :now - min(created_at)) FROM outbox_events WHERE status = 'PENDING')
                           AS oldest_pending_seconds
                """, new MapSqlParameterSource("now", JdbcValues.ts(now)), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pending", rs.getLong("pending"));
            m.put("failed", rs.getLong("failed"));
            double oldest = rs.getDouble("oldest_pending_seconds");
            m.put("oldestPendingSeconds", rs.wasNull() ? 0.0 : oldest);
            return m;
        });
        return new ProcessingMetrics(now, window.toMinutes(), throughput, latency, outbox);
    }

    /**
     * Selection coverage of one target marketplace, by top-level category of the reference assortment.
     *
     * <p>The reference assortment is every active canonical product with at least one active listing from an
     * enabled REFERENCE source; its category is the first segment of that reference listing's normalized
     * category (reference taxonomies are consistent, marketplace ones are not). A product is <em>carried</em> when
     * an active target listing is linked to it; otherwise it is counted under its gap status, or as
     * <em>not evaluated</em> when no reconciliation has assessed it yet. Carried wins over a stale gap row, so
     * the parts always add up to the reference count.
     */
    public List<CoverageRow> coverage(long targetSourceId) {
        return jdbc.query("""
                WITH ref AS (
                    SELECT k.canonical_product_id AS cp,
                           min(coalesce(split_part(l.normalized_category, ' > ', 1), '')) AS root
                    FROM listing_product_links k
                    JOIN product_listings l ON l.listing_id = k.listing_id AND l.status = 'ACTIVE'
                    JOIN catalog_sources s ON s.source_id = l.source_id AND s.role = 'REFERENCE' AND s.enabled
                    JOIN canonical_products c ON c.canonical_product_id = k.canonical_product_id
                                             AND c.status = 'ACTIVE'
                    GROUP BY k.canonical_product_id
                ), carried AS (
                    SELECT DISTINCT k.canonical_product_id AS cp
                    FROM listing_product_links k
                    JOIN product_listings l ON l.listing_id = k.listing_id AND l.status = 'ACTIVE'
                                            AND l.source_id = :target
                ), gap AS (
                    SELECT canonical_product_id AS cp, status FROM selection_gaps
                    WHERE target_source_id = :target AND status IN ('OPEN', 'CONFIRMED', 'DISMISSED')
                )
                SELECT CASE WHEN ref.root = '' THEN 'uncategorized' ELSE ref.root END AS category,
                       count(*) AS reference_products,
                       count(*) FILTER (WHERE carried.cp IS NOT NULL) AS carried,
                       count(*) FILTER (WHERE carried.cp IS NULL AND gap.status = 'OPEN') AS open_gaps,
                       count(*) FILTER (WHERE carried.cp IS NULL AND gap.status = 'CONFIRMED') AS confirmed_gaps,
                       count(*) FILTER (WHERE carried.cp IS NULL AND gap.status = 'DISMISSED') AS dismissed_gaps
                FROM ref
                LEFT JOIN carried ON carried.cp = ref.cp
                LEFT JOIN gap ON gap.cp = ref.cp
                GROUP BY 1
                ORDER BY reference_products DESC, category
                """, new MapSqlParameterSource("target", targetSourceId), (rs, i) -> {
            long reference = rs.getLong("reference_products");
            long carried = rs.getLong("carried");
            long open = rs.getLong("open_gaps");
            long confirmed = rs.getLong("confirmed_gaps");
            long dismissed = rs.getLong("dismissed_gaps");
            return new CoverageRow(rs.getString("category"), reference, carried, open, confirmed, dismissed,
                    reference - carried - open - confirmed - dismissed);
        });
    }

    private Map<String, Long> counts(String sql, MapSqlParameterSource p) {
        Map<String, Long> m = new TreeMap<>();
        jdbc.query(sql, p, rs -> {
            m.put(rs.getString(1), rs.getLong(2));
        });
        return m;
    }

    public record Summary(Instant generatedAt, Map<String, Long> sourcesByRole, Map<String, Long> listingsByStatus,
                          long canonicalProducts, Map<String, Long> matchCandidatesByReviewStatus,
                          Map<String, Long> gapsByStatus, Map<String, Long> openGapsByPriority,
                          Map<String, Long> eventsLast24hByOutcome, Map<String, Long> outboxBacklog,
                          Map<String, Object> lastReconciliation) {
    }

    public record ThroughputPoint(Instant minute, String outcome, long events) {
    }

    /** One category of the reference assortment; the five counts after {@code referenceProducts} sum to it. */
    public record CoverageRow(String category, long referenceProducts, long carried, long openGaps,
                              long confirmedGaps, long dismissedGaps, long notEvaluated) {
    }

    public record LatencyStats(String ingestionPath, long events, double p50Ms, double p95Ms, double p99Ms, double maxMs) {
    }

    public record ProcessingMetrics(Instant generatedAt, long windowMinutes, List<ThroughputPoint> throughput,
                                    List<LatencyStats> latencyByPath, Map<String, Object> outbox) {
    }
}
