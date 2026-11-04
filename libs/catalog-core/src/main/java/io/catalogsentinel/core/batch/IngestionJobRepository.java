package io.catalogsentinel.core.batch;

import io.catalogsentinel.core.batch.IngestionJob.IngestionJobStatus;
import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.common.PageRequest;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Repository
public class IngestionJobRepository {

    private static final String SELECT = """
            SELECT j.ingestion_job_id, j.job_key, j.source_id, s.code AS source_code, j.status, j.manifest::text AS manifest,
                   j.input_uri, j.output_uri, j.rejected_uri, j.rows_total, j.rows_valid, j.rows_rejected,
                   j.events_published, j.error_summary::text AS error_summary, j.attempt, j.created_at, j.started_at,
                   j.finished_at, j.updated_at
            FROM ingestion_jobs j
            JOIN catalog_sources s ON s.source_id = j.source_id
            """;

    private static final RowMapper<IngestionJob> MAPPER = (rs, i) -> new IngestionJob(
            rs.getLong("ingestion_job_id"), rs.getString("job_key"), rs.getLong("source_id"), rs.getString("source_code"),
            IngestionJobStatus.valueOf(rs.getString("status")), rs.getString("manifest"), rs.getString("input_uri"),
            rs.getString("output_uri"), rs.getString("rejected_uri"), JdbcValues.nullableLong(rs, "rows_total"),
            JdbcValues.nullableLong(rs, "rows_valid"), JdbcValues.nullableLong(rs, "rows_rejected"),
            JdbcValues.nullableLong(rs, "events_published"), rs.getString("error_summary"), rs.getInt("attempt"),
            JdbcValues.instant(rs, "created_at"), JdbcValues.instant(rs, "started_at"),
            JdbcValues.instant(rs, "finished_at"), JdbcValues.instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public IngestionJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a REGISTERED job; empty when the job key already exists (the caller loads and compares it). */
    public Optional<Long> insertIfAbsent(String jobKey, long sourceId, String manifestJson, String inputUri,
                                         String outputUri, String rejectedUri, Long rowsTotal, Instant now) {
        return jdbc.query("""
                INSERT INTO ingestion_jobs (job_key, source_id, manifest, input_uri, output_uri, rejected_uri, rows_total,
                                            created_at, updated_at)
                VALUES (:key, :source, CAST(:manifest AS jsonb), :input, :output, :rejected, :rowsTotal, :now, :now)
                ON CONFLICT (job_key) DO NOTHING
                RETURNING ingestion_job_id
                """, new MapSqlParameterSource()
                .addValue("key", jobKey)
                .addValue("source", sourceId)
                .addValue("manifest", manifestJson)
                .addValue("input", inputUri, Types.VARCHAR)
                .addValue("output", outputUri, Types.VARCHAR)
                .addValue("rejected", rejectedUri, Types.VARCHAR)
                .addValue("rowsTotal", rowsTotal, Types.BIGINT)
                .addValue("now", JdbcValues.ts(now)), (rs, i) -> rs.getLong(1)).stream().findFirst();
    }

    public Optional<IngestionJob> find(long jobId) {
        return jdbc.query(SELECT + " WHERE j.ingestion_job_id = :id", new MapSqlParameterSource("id", jobId), MAPPER)
                .stream().findFirst();
    }

    /** Row lock for state transitions (caller holds a transaction). */
    public Optional<IngestionJob> findForUpdate(long jobId) {
        return jdbc.query(SELECT + " WHERE j.ingestion_job_id = :id FOR UPDATE OF j", new MapSqlParameterSource("id", jobId),
                MAPPER).stream().findFirst();
    }

    public Optional<IngestionJob> findByKey(String jobKey) {
        return jdbc.query(SELECT + " WHERE j.job_key = :key", new MapSqlParameterSource("key", jobKey), MAPPER)
                .stream().findFirst();
    }

    /** Writes every mutable column of {@code job}; {@code updated_at} is maintained by trigger. */
    public void update(IngestionJob job) {
        jdbc.update("""
                UPDATE ingestion_jobs SET status = :status, output_uri = :output, rejected_uri = :rejected,
                       rows_total = :rowsTotal, rows_valid = :rowsValid, rows_rejected = :rowsRejected,
                       events_published = :published, error_summary = CAST(:errors AS jsonb), attempt = :attempt,
                       started_at = :startedAt, finished_at = :finishedAt
                WHERE ingestion_job_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", job.ingestionJobId())
                .addValue("status", job.status().name())
                .addValue("output", job.outputUri(), Types.VARCHAR)
                .addValue("rejected", job.rejectedUri(), Types.VARCHAR)
                .addValue("rowsTotal", job.rowsTotal(), Types.BIGINT)
                .addValue("rowsValid", job.rowsValid(), Types.BIGINT)
                .addValue("rowsRejected", job.rowsRejected(), Types.BIGINT)
                .addValue("published", job.eventsPublished(), Types.BIGINT)
                .addValue("errors", job.errorSummaryJson())
                .addValue("attempt", job.attempt())
                .addValue("startedAt", JdbcValues.ts(job.startedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("finishedAt", JdbcValues.ts(job.finishedAt()), Types.TIMESTAMP_WITH_TIMEZONE));
    }

    /** Newest first, keyset-paginated on the identity column (monotonic with creation order). */
    public List<IngestionJob> list(IngestionJobStatus status, Long sourceId, Long beforeId, PageRequest page) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("limit", page.fetchSize());
        if (status != null) {
            sql.append(" AND j.status = :status");
            p.addValue("status", status.name());
        }
        if (sourceId != null) {
            sql.append(" AND j.source_id = :source");
            p.addValue("source", sourceId);
        }
        if (beforeId != null) {
            sql.append(" AND j.ingestion_job_id < :before");
            p.addValue("before", beforeId);
        }
        sql.append(" ORDER BY j.ingestion_job_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), p, MAPPER);
    }

    /**
     * Processing progress derived from the idempotency ledger (never counted on the job row, which would become a
     * hot row): processed events by outcome plus first/last processing time.
     */
    public Progress progress(long jobId) {
        Map<String, Long> byOutcome = new TreeMap<>();
        Instant[] range = new Instant[2];
        jdbc.query("""
                SELECT outcome, count(*) AS n, min(processed_at) AS first_at, max(processed_at) AS last_at
                FROM processed_events WHERE ingestion_job_id = :id GROUP BY outcome
                """, new MapSqlParameterSource("id", jobId), rs -> {
            byOutcome.put(rs.getString("outcome"), rs.getLong("n"));
            Instant first = JdbcValues.instant(rs, "first_at");
            Instant last = JdbcValues.instant(rs, "last_at");
            if (range[0] == null || first.isBefore(range[0])) {
                range[0] = first;
            }
            if (range[1] == null || last.isAfter(range[1])) {
                range[1] = last;
            }
        });
        long processed = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        return new Progress(processed, byOutcome, range[0], range[1]);
    }

    public record Progress(long processedEvents, Map<String, Long> byOutcome, Instant firstProcessedAt,
                           Instant lastProcessedAt) {
    }
}
