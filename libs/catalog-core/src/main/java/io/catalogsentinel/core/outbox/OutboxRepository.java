package io.catalogsentinel.core.outbox;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.json.JsonCodec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public class OutboxRepository {

    private static final String INSERT_SQL = """
            INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, headers,
                                       next_attempt_at, created_at)
            VALUES (:eventId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb), CAST(:headers AS jsonb),
                    :now, :now)
            ON CONFLICT (event_id) DO NOTHING
            """;

    /**
     * Claims due rows for publication. FOR UPDATE SKIP LOCKED lets several relay instances poll concurrently
     * without blocking each other or publishing the same row twice within overlapping transactions.
     */
    private static final String CLAIM_SQL = """
            SELECT outbox_id, event_id, aggregate_type, aggregate_id, event_type, payload::text AS payload,
                   headers::text AS headers, attempts, created_at
            FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY outbox_id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public OutboxRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Appends a message in the caller's transaction. Idempotent on {@code eventId}. Scheduling timestamps come
     * from the application clock, the same clock {@link #claimDue} compares against; mixing database and
     * application time would make rows look "not yet due" whenever the two clocks disagree.
     */
    public void append(OutboxMessage message, Instant now) {
        jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                .addValue("now", JdbcValues.ts(now))
                .addValue("eventId", message.eventId())
                .addValue("aggregateType", message.aggregateType())
                .addValue("aggregateId", message.aggregateId())
                .addValue("eventType", message.eventType())
                .addValue("payload", message.payloadJson())
                .addValue("headers", json.write(message.headers())));
    }

    /** Must be called inside a transaction; rows stay locked until it ends. */
    public List<OutboxMessage> claimDue(Instant now, int limit) {
        return jdbc.query(CLAIM_SQL, new MapSqlParameterSource()
                        .addValue("now", JdbcValues.ts(now))
                        .addValue("limit", limit),
                (rs, i) -> new OutboxMessage(
                        rs.getLong("outbox_id"),
                        JdbcValues.uuid(rs, "event_id"),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        json.readStringMap(rs.getString("headers")),
                        rs.getInt("attempts"),
                        JdbcValues.instant(rs, "created_at")));
    }

    public void markPublished(Collection<Long> outboxIds, Instant now) {
        if (outboxIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = :now, attempts = attempts + 1, last_error = NULL
                WHERE outbox_id IN (:ids)
                """, new MapSqlParameterSource().addValue("ids", outboxIds).addValue("now", JdbcValues.ts(now)));
    }

    /**
     * Records a failed publish attempt. After {@code maxAttempts} the row moves to FAILED (alerted on, and
     * replayable with {@link #requeueFailed}) instead of being retried forever.
     */
    public void recordFailedAttempt(long outboxId, String error, Instant nextAttemptAt, int maxAttempts) {
        jdbc.update("""
                UPDATE outbox_events
                SET attempts = attempts + 1,
                    last_error = :error,
                    next_attempt_at = :next,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END
                WHERE outbox_id = :id
                """, new MapSqlParameterSource()
                .addValue("id", outboxId)
                .addValue("error", JdbcValues.trimTo(error, 1000))
                .addValue("next", JdbcValues.ts(nextAttemptAt))
                .addValue("maxAttempts", maxAttempts));
    }

    /** Operator action: give FAILED rows a fresh set of attempts. */
    public int requeueFailed(Instant now) {
        return jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING', attempts = 0, next_attempt_at = :now
                WHERE status = 'FAILED'
                """, new MapSqlParameterSource("now", JdbcValues.ts(now)));
    }

    public OutboxStats stats(Instant now) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM outbox_events WHERE status = 'PENDING') AS pending,
                       (SELECT min(created_at) FROM outbox_events WHERE status = 'PENDING') AS oldest_pending,
                       (SELECT count(*) FROM outbox_events WHERE status = 'FAILED') AS failed
                """, new MapSqlParameterSource(), (rs, i) -> {
            Instant oldest = JdbcValues.instant(rs, "oldest_pending");
            return new OutboxStats(rs.getLong("pending"), rs.getLong("failed"),
                    oldest == null ? Duration.ZERO : Duration.between(oldest, now));
        });
    }

    /** Deletes published rows older than {@code cutoff}, at most {@code batchSize} per call. */
    public int purgePublishedBefore(Instant cutoff, int batchSize) {
        return jdbc.update("""
                DELETE FROM outbox_events WHERE outbox_id IN (
                    SELECT outbox_id FROM outbox_events
                    WHERE status = 'PUBLISHED' AND published_at < :cutoff
                    ORDER BY published_at LIMIT :batch)
                """, new MapSqlParameterSource().addValue("cutoff", JdbcValues.ts(cutoff)).addValue("batch", batchSize));
    }

    /** Backlog figures for health checks, dashboards and alarms. */
    public record OutboxStats(long pending, long failed, Duration oldestPendingAge) {
    }
}
