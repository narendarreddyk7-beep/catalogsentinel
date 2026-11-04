package io.catalogsentinel.core.review;

import io.catalogsentinel.core.common.JdbcValues;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Append-only audit trail (UPDATE/DELETE/TRUNCATE are rejected by database triggers). */
@Repository
public class ReviewDecisionRepository {

    private static final RowMapper<ReviewDecision> MAPPER = (rs, i) -> new ReviewDecision(
            rs.getLong("decision_id"), JdbcValues.nullableLong(rs, "match_candidate_id"),
            JdbcValues.nullableLong(rs, "gap_id"), rs.getString("decision"), rs.getString("previous_status"),
            rs.getString("new_status"), rs.getString("reviewer"), rs.getString("reason"),
            rs.getString("idempotency_key"), JdbcValues.instant(rs, "decided_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public ReviewDecisionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReviewDecision append(Long matchCandidateId, Long gapId, String decision, String previousStatus,
                                 String newStatus, String reviewer, String reason, String idempotencyKey, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO review_decisions (match_candidate_id, gap_id, decision, previous_status, new_status, reviewer,
                                              reason, idempotency_key, decided_at)
                VALUES (:candidate, :gap, :decision, :previous, :next, :reviewer, :reason, :key, :at)
                RETURNING *
                """, new MapSqlParameterSource()
                .addValue("candidate", matchCandidateId, Types.BIGINT)
                .addValue("gap", gapId, Types.BIGINT)
                .addValue("decision", decision)
                .addValue("previous", previousStatus)
                .addValue("next", newStatus)
                .addValue("reviewer", JdbcValues.trimTo(reviewer, 128))
                .addValue("reason", JdbcValues.trimTo(reason, 2000), Types.VARCHAR)
                .addValue("key", idempotencyKey, Types.VARCHAR)
                .addValue("at", JdbcValues.ts(at)), MAPPER);
    }

    public Optional<ReviewDecision> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM review_decisions WHERE idempotency_key = :key",
                new MapSqlParameterSource("key", key), MAPPER).stream().findFirst();
    }

    public List<ReviewDecision> forGap(long gapId) {
        return jdbc.query("SELECT * FROM review_decisions WHERE gap_id = :id ORDER BY decided_at, decision_id",
                new MapSqlParameterSource("id", gapId), MAPPER);
    }

    public List<ReviewDecision> forCandidate(long candidateId) {
        return jdbc.query("SELECT * FROM review_decisions WHERE match_candidate_id = :id ORDER BY decided_at, decision_id",
                new MapSqlParameterSource("id", candidateId), MAPPER);
    }

    public record ReviewDecision(long decisionId, Long matchCandidateId, Long gapId, String decision,
                                 String previousStatus, String newStatus, String reviewer, String reason,
                                 String idempotencyKey, Instant decidedAt) {
    }
}
