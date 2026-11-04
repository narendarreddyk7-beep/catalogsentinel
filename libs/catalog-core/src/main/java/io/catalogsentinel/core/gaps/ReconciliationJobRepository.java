package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.json.JsonCodec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ReconciliationJobRepository {

    private static final String SELECT = """
            SELECT j.job_id, j.target_source_id, t.code AS target_code, j.reference_source_id, r.code AS reference_code,
                   j.status, j.requested_by, j.idempotency_key, j.stats::text AS stats, j.error_message, j.created_at,
                   j.started_at, j.finished_at, j.updated_at, j.attempt, j.max_attempts, j.next_attempt_at,
                   j.lease_owner, j.lease_expires_at
            FROM reconciliation_jobs j
            JOIN catalog_sources t ON t.source_id = j.target_source_id
            LEFT JOIN catalog_sources r ON r.source_id = j.reference_source_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<ReconciliationJob> mapper;
    private final JsonCodec json;

    public ReconciliationJobRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new ReconciliationJob(rs.getLong("job_id"), rs.getLong("target_source_id"),
                rs.getString("target_code"), JdbcValues.nullableLong(rs, "reference_source_id"),
                rs.getString("reference_code"), rs.getString("status"), rs.getString("requested_by"),
                rs.getString("idempotency_key"), rs.getString("stats"), rs.getString("error_message"),
                JdbcValues.instant(rs, "created_at"), JdbcValues.instant(rs, "started_at"),
                JdbcValues.instant(rs, "finished_at"), JdbcValues.instant(rs, "updated_at"), rs.getInt("attempt"),
                rs.getInt("max_attempts"), JdbcValues.instant(rs, "next_attempt_at"), rs.getString("lease_owner"),
                JdbcValues.instant(rs, "lease_expires_at"));
    }

    /**
     * Inserts a PENDING job. Returns empty when another job for the target is already PENDING/RUNNING (enforced
     * by the partial unique index) or the idempotency key exists; the caller distinguishes the two.
     */
    public Optional<Long> insertPending(long targetSourceId, Long referenceSourceId, String requestedBy,
                                        String idempotencyKey) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    INSERT INTO reconciliation_jobs (target_source_id, reference_source_id, requested_by, idempotency_key)
                    VALUES (:target, :reference, :requestedBy, :key)
                    RETURNING job_id
                    """, new MapSqlParameterSource()
                    .addValue("target", targetSourceId)
                    .addValue("reference", referenceSourceId, Types.BIGINT)
                    .addValue("requestedBy", requestedBy)
                    .addValue("key", idempotencyKey, Types.VARCHAR), Long.class));
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Optional<ReconciliationJob> find(long jobId) {
        return jdbc.query(SELECT + " WHERE j.job_id = :id", new MapSqlParameterSource("id", jobId), mapper)
                .stream().findFirst();
    }

    public Optional<ReconciliationJob> findByIdempotencyKey(String key) {
        return jdbc.query(SELECT + " WHERE j.idempotency_key = :key", new MapSqlParameterSource("key", key), mapper)
                .stream().findFirst();
    }

    public Optional<ReconciliationJob> findActive(long targetSourceId) {
        return jdbc.query(SELECT + " WHERE j.target_source_id = :t AND j.status IN ('PENDING', 'RUNNING')",
                new MapSqlParameterSource("t", targetSourceId), mapper).stream().findFirst();
    }

    public List<ReconciliationJob> recent(Long targetSourceId, Long beforeId, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit);
        if (targetSourceId != null) {
            sql.append(" AND j.target_source_id = :t");
            p.addValue("t", targetSourceId);
        }
        if (beforeId != null) {
            sql.append(" AND j.job_id < :before");
            p.addValue("before", beforeId);
        }
        sql.append(" ORDER BY j.job_id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), p, mapper);
    }

    /**
     * Claims a job for {@code owner}: a due PENDING job, or a RUNNING job whose lease expired (its runner died),
     * as long as attempts remain. {@code FOR UPDATE SKIP LOCKED} lets any number of pollers claim concurrently
     * without blocking or claiming the same attempt twice. The claim is a lease in database time; the runner must
     * renew it while working. With {@code jobId} null the oldest claimable job is taken.
     */
    public Optional<ReconciliationJob> claim(Long jobId, String owner, Duration lease) {
        List<Long> claimed = jdbc.query("""
                WITH next AS (
                    SELECT job_id FROM reconciliation_jobs
                    WHERE ((status = 'PENDING' AND next_attempt_at <= now())
                        OR (status = 'RUNNING' AND lease_expires_at < now()))
                      AND attempt < max_attempts
                      AND (CAST(:id AS bigint) IS NULL OR job_id = CAST(:id AS bigint))
                    ORDER BY job_id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE reconciliation_jobs j
                SET status = 'RUNNING', attempt = j.attempt + 1, lease_owner = :owner,
                    lease_expires_at = now() + make_interval(secs => :leaseSeconds), started_at = now(),
                    finished_at = NULL
                FROM next WHERE j.job_id = next.job_id
                RETURNING j.job_id
                """, new MapSqlParameterSource().addValue("id", jobId, Types.BIGINT).addValue("owner", owner)
                .addValue("leaseSeconds", (double) lease.toSeconds()), (rs, i) -> rs.getLong(1));
        return claimed.isEmpty() ? Optional.empty() : find(claimed.get(0));
    }

    /**
     * Extends the lease and saves progress. Returns false when {@code owner} no longer holds the job (the lease
     * expired and another runner claimed it): the caller must stop and write nothing more (fencing).
     */
    public boolean renewLease(long jobId, String owner, Duration lease, Map<String, Long> stats) {
        return jdbc.update("""
                UPDATE reconciliation_jobs
                SET lease_expires_at = now() + make_interval(secs => :leaseSeconds), stats = CAST(:stats AS jsonb)
                WHERE job_id = :id AND status = 'RUNNING' AND lease_owner = :owner
                """, new MapSqlParameterSource().addValue("id", jobId).addValue("owner", owner)
                .addValue("leaseSeconds", (double) lease.toSeconds()).addValue("stats", json.write(stats))) == 1;
    }

    /** RUNNING -> SUCCEEDED if {@code owner} still holds the lease. */
    public boolean complete(long jobId, String owner, Map<String, Long> stats) {
        return jdbc.update("""
                UPDATE reconciliation_jobs
                SET status = 'SUCCEEDED', stats = CAST(:stats AS jsonb), error_message = NULL, finished_at = now(),
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE job_id = :id AND status = 'RUNNING' AND lease_owner = :owner
                """, new MapSqlParameterSource().addValue("id", jobId).addValue("owner", owner)
                .addValue("stats", json.write(stats))) == 1;
    }

    /**
     * Records a failed attempt by {@code owner}: back to PENDING after {@code backoff} while attempts remain and the
     * failure is retryable, otherwise FAILED. The error stays visible either way. Returns the new status, or empty
     * if {@code owner} no longer held the job.
     */
    public Optional<String> failAttempt(long jobId, String owner, String error, Map<String, Long> stats,
                                        Duration backoff, boolean permanent) {
        return jdbc.query("""
                UPDATE reconciliation_jobs
                SET status = CASE WHEN :permanent OR attempt >= max_attempts THEN 'FAILED' ELSE 'PENDING' END,
                    finished_at = CASE WHEN :permanent OR attempt >= max_attempts THEN now() END,
                    next_attempt_at = CASE WHEN :permanent OR attempt >= max_attempts THEN next_attempt_at
                                           ELSE now() + make_interval(secs => :backoffSeconds) END,
                    error_message = :error, stats = CAST(:stats AS jsonb), lease_owner = NULL, lease_expires_at = NULL
                WHERE job_id = :id AND status = 'RUNNING' AND lease_owner = :owner
                RETURNING status
                """, new MapSqlParameterSource().addValue("id", jobId).addValue("owner", owner)
                .addValue("permanent", permanent).addValue("backoffSeconds", (double) backoff.toSeconds())
                .addValue("error", JdbcValues.trimTo(error, 2000), Types.VARCHAR).addValue("stats", json.write(stats)),
                (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    /**
     * Fails RUNNING jobs whose lease expired on their last allowed attempt (the runner died every time), so the
     * target's one-active-job slot is freed and the failure is visible. Returns how many were failed.
     */
    public int failExhaustedLeases() {
        return jdbc.update("""
                UPDATE reconciliation_jobs
                SET status = 'FAILED', finished_at = now(),
                    error_message = 'lease expired during attempt ' || attempt || ' of ' || max_attempts
                        || ': the runner stopped (crash, kill or lost database connection); last owner ' || lease_owner,
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE status = 'RUNNING' AND lease_expires_at < now() AND attempt >= max_attempts
                """, new MapSqlParameterSource());
    }

    /**
     * @param statsJson     job statistics exactly as stored (embedded raw in API responses)
     * @param attempt       attempts started so far (a claim increments it)
     * @param nextAttemptAt when a PENDING job becomes claimable (later than creation after a failed attempt)
     * @param leaseOwner    runner holding a RUNNING job; null otherwise
     */
    public record ReconciliationJob(long jobId, long targetSourceId, String targetSourceCode, Long referenceSourceId,
                                    String referenceSourceCode, String status, String requestedBy,
                                    String idempotencyKey, String statsJson, String errorMessage, Instant createdAt,
                                    Instant startedAt, Instant finishedAt, Instant updatedAt, int attempt,
                                    int maxAttempts, Instant nextAttemptAt, String leaseOwner, Instant leaseExpiresAt) {
    }
}
