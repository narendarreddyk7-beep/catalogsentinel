package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.domain.matching.MatchDecision;
import io.catalogsentinel.domain.matching.MatchResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class MatchCandidateRepository {

    /**
     * Upsert keyed by the canonical pair. A human decision (APPROVED/REJECTED) survives re-scoring for as long
     * as both listings' content hashes are unchanged; once either listing changes, the decision is stale and the
     * fresh machine decision replaces it.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO match_candidates (left_listing_id, right_listing_id, score, decision, feature_scores, evidence,
                                          matcher_version, retrieval_method, left_content_hash, right_content_hash,
                                          review_status)
            VALUES (:left, :right, :score, :decision, CAST(:features AS jsonb), CAST(:evidence AS jsonb),
                    :matcherVersion, :retrieval, :leftHash, :rightHash, :reviewStatus)
            ON CONFLICT (left_listing_id, right_listing_id) DO UPDATE SET
                score = EXCLUDED.score,
                decision = EXCLUDED.decision,
                feature_scores = EXCLUDED.feature_scores,
                evidence = EXCLUDED.evidence,
                matcher_version = EXCLUDED.matcher_version,
                retrieval_method = EXCLUDED.retrieval_method,
                review_status = CASE WHEN %1$s THEN match_candidates.review_status ELSE EXCLUDED.review_status END,
                reviewed_by = CASE WHEN %1$s THEN match_candidates.reviewed_by ELSE NULL END,
                reviewed_at = CASE WHEN %1$s THEN match_candidates.reviewed_at ELSE NULL END,
                left_content_hash = EXCLUDED.left_content_hash,
                right_content_hash = EXCLUDED.right_content_hash
            RETURNING candidate_id, review_status
            """.formatted("""
            (match_candidates.review_status IN ('APPROVED', 'REJECTED')
                     AND match_candidates.left_content_hash = EXCLUDED.left_content_hash
                     AND match_candidates.right_content_hash = EXCLUDED.right_content_hash)""");

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    public MatchCandidateRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public StoredCandidate upsert(MatchableListing a, MatchableListing b, MatchResult result, String retrievalMethod) {
        MatchableListing left = a.listingId() < b.listingId() ? a : b;
        MatchableListing right = left == a ? b : a;
        String reviewStatus = switch (result.decision()) {
            case AUTO_MATCH -> "AUTO_ACCEPTED";
            case REVIEW -> "PENDING";
            case NO_MATCH -> "NOT_APPLICABLE";
        };
        return jdbc.queryForObject(UPSERT_SQL, new MapSqlParameterSource()
                        .addValue("left", left.listingId())
                        .addValue("right", right.listingId())
                        .addValue("score", JdbcValues.score(result.score()))
                        .addValue("decision", result.decision().name())
                        .addValue("features", json.write(result.features().asMap()))
                        .addValue("evidence", json.write(result.evidence()))
                        .addValue("matcherVersion", result.matcherVersion())
                        .addValue("retrieval", retrievalMethod)
                        .addValue("leftHash", left.contentHash())
                        .addValue("rightHash", right.contentHash())
                        .addValue("reviewStatus", reviewStatus),
                (rs, i) -> new StoredCandidate(rs.getLong("candidate_id"), left.listingId(), right.listingId(),
                        result.score(), result.decision(), rs.getString("review_status"),
                        result.hasEvidence("GTIN_MATCH")));
    }

    /**
     * A candidate is evidence about the two listings' content when it was scored. It is <em>current</em> while
     * both listings are ACTIVE and both content hashes still equal the stored ones; only current candidates may
     * justify links, merges, gap evidence or new review decisions (docs/adr/0013-match-evidence-currency.md).
     * Use with {@link #JOIN_LISTINGS}, which aliases the candidate {@code m} and its listings {@code la}/{@code lb}.
     */
    static final String CURRENT = """
            la.status = 'ACTIVE' AND lb.status = 'ACTIVE'
            AND la.content_hash = m.left_content_hash AND lb.content_hash = m.right_content_hash""";
    static final String STALE = """
            (la.status <> 'ACTIVE' OR lb.status <> 'ACTIVE'
             OR la.content_hash IS DISTINCT FROM m.left_content_hash
             OR lb.content_hash IS DISTINCT FROM m.right_content_hash)""";
    static final String JOIN_LISTINGS = """
            JOIN product_listings la ON la.listing_id = m.left_listing_id
            JOIN product_listings lb ON lb.listing_id = m.right_listing_id""";
    /** Decisions that can justify a link or a merge: auto matches and human approvals. */
    static final String LINKABLE = """
            (m.review_status = 'APPROVED' OR (m.decision = 'AUTO_MATCH' AND m.review_status = 'AUTO_ACCEPTED'))""";
    private static final String STORED_COLUMNS = """
            m.candidate_id, m.left_listing_id, m.right_listing_id, m.score, m.decision, m.review_status,
            m.evidence @> '[{"code": "GTIN_MATCH"}]'::jsonb AS gtin_exact""";

    /** Current candidates involving the listing that can justify a link, approvals first. */
    public List<StoredCandidate> linkableFor(long listingId) {
        return jdbc.query("SELECT " + STORED_COLUMNS + " FROM match_candidates m " + JOIN_LISTINGS + """
                 WHERE (m.left_listing_id = :id OR m.right_listing_id = :id)
                  AND %s AND %s
                ORDER BY (m.review_status = 'APPROVED') DESC, m.score DESC, m.candidate_id
                """.formatted(LINKABLE, CURRENT), new MapSqlParameterSource("id", listingId), STORED);
    }

    /** Current linkable candidates with both listings in the given set (the evidence graph of one product). */
    public List<StoredCandidate> currentLinkableAmong(Collection<Long> listingIds) {
        if (listingIds.size() < 2) {
            return List.of();
        }
        return jdbc.query("SELECT " + STORED_COLUMNS + " FROM match_candidates m " + JOIN_LISTINGS + """
                 WHERE m.left_listing_id IN (:ids) AND m.right_listing_id IN (:ids)
                  AND %s AND %s
                ORDER BY (m.review_status = 'APPROVED') DESC, m.score DESC, m.candidate_id
                """.formatted(LINKABLE, CURRENT), new MapSqlParameterSource("ids", listingIds), STORED);
    }

    /**
     * Marks every candidate involving the listing that no longer describes current content (either listing
     * changed or was withdrawn) as SUPERSEDED, whatever its status, and returns what each one was. Human
     * decisions lose their reviewer columns here; the append-only audit trail keeps them, and the caller records
     * the supersession there. Idempotent: superseded rows are not touched again.
     */
    public List<Superseded> supersedeStaleFor(long listingId) {
        return jdbc.query("""
                WITH stale AS (
                    SELECT m.candidate_id, m.review_status AS previous_status, m.reviewed_by AS previous_reviewer,
                           m.left_listing_id, m.right_listing_id,
                           concat_ws('; ',
                               CASE WHEN la.status <> 'ACTIVE' THEN 'listing ' || la.listing_id || ' was withdrawn'
                                    WHEN la.content_hash IS DISTINCT FROM m.left_content_hash
                                        THEN 'listing ' || la.listing_id || ' changed' END,
                               CASE WHEN lb.status <> 'ACTIVE' THEN 'listing ' || lb.listing_id || ' was withdrawn'
                                    WHEN lb.content_hash IS DISTINCT FROM m.right_content_hash
                                        THEN 'listing ' || lb.listing_id || ' changed' END) AS reason
                    FROM match_candidates m
                """ + JOIN_LISTINGS + """
                    WHERE (m.left_listing_id = :id OR m.right_listing_id = :id)
                      AND m.review_status <> 'SUPERSEDED' AND %s
                    FOR UPDATE OF m
                ), superseded AS (
                    UPDATE match_candidates m SET review_status = 'SUPERSEDED', reviewed_by = NULL, reviewed_at = NULL
                    FROM stale WHERE m.candidate_id = stale.candidate_id
                    RETURNING m.candidate_id
                )
                SELECT s.candidate_id, s.previous_status, s.previous_reviewer, s.left_listing_id, s.right_listing_id,
                       s.reason
                FROM stale s JOIN superseded u ON u.candidate_id = s.candidate_id
                ORDER BY s.candidate_id
                """.formatted(STALE), new MapSqlParameterSource("id", listingId),
                (rs, i) -> new Superseded(rs.getLong("candidate_id"), rs.getString("previous_status"),
                        rs.getString("previous_reviewer"), rs.getLong("left_listing_id"), rs.getLong("right_listing_id"),
                        rs.getString("reason")));
    }

    /**
     * Whether the candidate still describes both listings' current content. Takes share locks on both listing
     * rows so their content cannot change until the caller's transaction ends (used before recording a review).
     */
    public Currency lockCurrency(long candidateId) {
        return jdbc.query("""
                SELECT la.listing_id AS left_id, la.status AS left_status,
                       la.content_hash IS NOT DISTINCT FROM m.left_content_hash AS left_unchanged,
                       lb.listing_id AS right_id, lb.status AS right_status,
                       lb.content_hash IS NOT DISTINCT FROM m.right_content_hash AS right_unchanged
                FROM match_candidates m
                """ + JOIN_LISTINGS + """
                 WHERE m.candidate_id = :id
                FOR SHARE OF la, lb
                """, new MapSqlParameterSource("id", candidateId), (rs, i) -> {
            StringBuilder why = new StringBuilder();
            for (String side : List.of("left", "right")) {
                long id = rs.getLong(side + "_id");
                if (!"ACTIVE".equals(rs.getString(side + "_status"))) {
                    why.append(why.isEmpty() ? "" : "; ").append("listing ").append(id).append(" was withdrawn");
                } else if (!rs.getBoolean(side + "_unchanged")) {
                    why.append(why.isEmpty() ? "" : "; ").append("listing ").append(id).append(" changed");
                }
            }
            return new Currency(why.isEmpty(), why.toString());
        }).stream().findFirst().orElse(new Currency(false, "candidate not found"));
    }

    public Optional<StoredCandidate> findForUpdate(long candidateId) {
        return jdbc.query("""
                SELECT candidate_id, left_listing_id, right_listing_id, score, decision, review_status,
                       evidence @> '[{"code": "GTIN_MATCH"}]'::jsonb AS gtin_exact
                FROM match_candidates WHERE candidate_id = :id FOR UPDATE
                """, new MapSqlParameterSource("id", candidateId), STORED).stream().findFirst();
    }

    public void recordReview(long candidateId, String reviewStatus, String reviewer, Instant at) {
        jdbc.update("""
                UPDATE match_candidates SET review_status = :status, reviewed_by = :reviewer, reviewed_at = :at
                WHERE candidate_id = :id
                """, new MapSqlParameterSource().addValue("id", candidateId).addValue("status", reviewStatus)
                .addValue("reviewer", reviewer).addValue("at", JdbcValues.ts(at)));
    }

    private static final RowMapper<StoredCandidate> STORED = (rs, i) -> new StoredCandidate(
            rs.getLong("candidate_id"), rs.getLong("left_listing_id"), rs.getLong("right_listing_id"),
            rs.getBigDecimal("score").doubleValue(), MatchDecision.valueOf(rs.getString("decision")),
            rs.getString("review_status"), rs.getBoolean("gtin_exact"));

    public record StoredCandidate(long candidateId, long leftListingId, long rightListingId, double score,
                                  MatchDecision decision, String reviewStatus, boolean gtinExact) {
        public long other(long listingId) {
            return listingId == leftListingId ? rightListingId : leftListingId;
        }

        public boolean approved() {
            return "APPROVED".equals(reviewStatus);
        }
    }

    /** A candidate marked SUPERSEDED, with the status it had before. */
    public record Superseded(long candidateId, String previousStatus, String previousReviewer, long leftListingId,
                             long rightListingId, String reason) {
        public boolean wasHumanDecision() {
            return "APPROVED".equals(previousStatus) || "REJECTED".equals(previousStatus);
        }
    }

    /** @param reason empty when current; otherwise which listing changed or was withdrawn */
    public record Currency(boolean current, String reason) {
    }
}
