package io.catalogsentinel.core.review;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.gaps.GapRepository;
import io.catalogsentinel.core.matching.MatchCandidateRepository;
import io.catalogsentinel.core.matching.MatchCandidateRepository.StoredCandidate;
import io.catalogsentinel.core.matching.MatchingService;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.gap.GapDecision;
import io.catalogsentinel.domain.gap.GapLifecycle;
import io.catalogsentinel.domain.gap.GapStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Human review of match candidates and gaps. Every effective decision appends one row to the immutable
 * {@code review_decisions} audit trail in the same transaction as the state change. Requests may carry an
 * idempotency key so that client retries never double-apply a decision. A match decision is accepted only while
 * the candidate describes both listings' current content ({@code CANDIDATE_STALE} otherwise); when content later
 * changes, matching supersedes the decision and audits that (docs/adr/0013-match-evidence-currency.md).
 */
@Service
public class ReviewService {

    private static final Set<String> MATCH_REVIEWABLE = Set.of("PENDING", "APPROVED", "REJECTED", "AUTO_ACCEPTED",
            "NOT_APPLICABLE");

    private final MatchCandidateRepository candidates;
    private final GapRepository gaps;
    private final ReviewDecisionRepository audit;
    private final MatchingService matching;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReviewService(MatchCandidateRepository candidates, GapRepository gaps, ReviewDecisionRepository audit,
                         MatchingService matching, TransactionTemplate tx, Clock clock) {
        this.candidates = candidates;
        this.gaps = gaps;
        this.audit = audit;
        this.matching = matching;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Approves or rejects a candidate pair. Approval links both listings to one canonical product; rejecting an
     * auto-accepted pair splits them. Both listings are re-linked after the decision commits.
     */
    public DecisionResult decideMatch(long candidateId, String rawDecision, String reviewer, String reason,
                                      String idempotencyKey) {
        String decision = normalize(rawDecision, Set.of("APPROVE", "REJECT"));
        requireReviewer(reviewer);
        DecisionResult result = tx.execute(s -> {
            Optional<DecisionResult> replay = replay(idempotencyKey, candidateId, null, decision);
            if (replay.isPresent()) {
                return replay.get();
            }
            StoredCandidate c = candidates.findForUpdate(candidateId)
                    .orElseThrow(() -> new NotFoundException("match candidate", candidateId));
            if (!MATCH_REVIEWABLE.contains(c.reviewStatus())) {
                throw new ConflictException("CANDIDATE_NOT_REVIEWABLE",
                        "candidate is " + c.reviewStatus() + " (a listing changed or was withdrawn); re-match first");
            }
            // A decision applies to the content the candidate was scored on; it must still be the current content.
            // Share-locks both listings until commit, so their content cannot change under the decision.
            MatchCandidateRepository.Currency currency = candidates.lockCurrency(candidateId);
            if (!currency.current()) {
                throw new ConflictException("CANDIDATE_STALE", "candidate " + candidateId
                        + " was scored on earlier content (" + currency.reason() + "); it is re-matched automatically");
            }
            String next = decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
            if (next.equals(c.reviewStatus())) {
                return new DecisionResult(candidateId, c.reviewStatus(), next, false, null);
            }
            Instant now = clock.instant();
            candidates.recordReview(candidateId, next, reviewer, now);
            ReviewDecision d = audit.append(candidateId, null, decision, c.reviewStatus(), next, reviewer, reason,
                    idempotencyKey, now);
            return new DecisionResult(candidateId, c.reviewStatus(), next, true, d.decisionId());
        });
        if (result != null && result.changed()) {
            relink(candidateId);
        }
        return result;
    }

    public DecisionResult decideGap(long gapId, String rawDecision, String reviewer, String reason,
                                    String idempotencyKey) {
        GapDecision decision = GapDecision.valueOf(normalize(rawDecision, Set.of("CONFIRM", "DISMISS", "RESOLVE", "REOPEN")));
        requireReviewer(reviewer);
        return tx.execute(s -> {
            Optional<DecisionResult> replay = replay(idempotencyKey, null, gapId, decision.name());
            if (replay.isPresent()) {
                return replay.get();
            }
            GapRepository.GapRow gap = gaps.lock(gapId).orElseThrow(() -> new NotFoundException("gap", gapId));
            if (GapLifecycle.isNoOp(gap.status(), decision)) {
                return new DecisionResult(gapId, gap.status().name(), gap.status().name(), false, null);
            }
            GapStatus next = GapLifecycle.next(gap.status(), decision).orElseThrow(() -> new ConflictException(
                    "INVALID_TRANSITION", "cannot " + decision + " a gap that is " + gap.status()));
            Instant now = clock.instant();
            gaps.updateStatus(gapId, next, next == GapStatus.RESOLVED ? "REVIEWER_RESOLVED" : null, now);
            ReviewDecision d = audit.append(null, gapId, decision.name(), gap.status().name(), next.name(), reviewer,
                    reason, idempotencyKey, now);
            return new DecisionResult(gapId, gap.status().name(), next.name(), true, d.decisionId());
        });
    }

    private void relink(long candidateId) {
        StoredCandidate c = tx.execute(s -> candidates.findForUpdate(candidateId).orElse(null));
        if (c == null) {
            return;
        }
        matching.matchListing(c.leftListingId());
        matching.matchListing(c.rightListingId());
        matching.mergeDuplicateIdentities(100);
    }

    /**
     * Returns the stored result for a reused idempotency key; rejects reuse of a key for a different subject or
     * decision.
     */
    private Optional<DecisionResult> replay(String key, Long candidateId, Long gapId, String decision) {
        if (key == null) {
            return Optional.empty();
        }
        return audit.findByIdempotencyKey(key).map(d -> {
            boolean same = java.util.Objects.equals(d.matchCandidateId(), candidateId)
                    && java.util.Objects.equals(d.gapId(), gapId) && d.decision().equals(decision);
            if (!same) {
                throw new ConflictException("IDEMPOTENCY_KEY_REUSED",
                        "idempotency key was already used for a different decision");
            }
            long subject = candidateId != null ? candidateId : gapId;
            return new DecisionResult(subject, d.previousStatus(), d.newStatus(), false, d.decisionId());
        });
    }

    private static String normalize(String raw, Set<String> allowed) {
        String v = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new DomainValidationException("decision", "must be one of " + allowed.stream().sorted().toList());
        }
        return v;
    }

    private static void requireReviewer(String reviewer) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new DomainValidationException("reviewer", "is required");
        }
    }

    /**
     * @param changed    false for idempotent no-ops and replays
     * @param decisionId audit row id (null when nothing was recorded)
     */
    public record DecisionResult(long subjectId, String previousStatus, String newStatus, boolean changed,
                                 Long decisionId) {
    }
}
