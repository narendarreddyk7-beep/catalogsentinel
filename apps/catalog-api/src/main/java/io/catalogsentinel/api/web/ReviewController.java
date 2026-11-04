package io.catalogsentinel.api.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.gaps.GapQueryRepository;
import io.catalogsentinel.core.gaps.GapQueryRepository.GapView;
import io.catalogsentinel.core.matching.MatchQueryRepository;
import io.catalogsentinel.core.review.ReviewDecisionRepository;
import io.catalogsentinel.core.review.ReviewDecisionRepository.ReviewDecision;
import io.catalogsentinel.core.review.ReviewService;
import io.catalogsentinel.core.review.ReviewService.DecisionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Selection gaps and human review decisions (gaps and match candidates). */
@RestController
@Tag(name = "Gaps & review", description = "Priority-ranked selection gaps and the review workflow")
public class ReviewController {

    private final GapQueryRepository gaps;
    private final MatchQueryRepository matches;
    private final ReviewService reviews;
    private final ReviewDecisionRepository audit;

    public ReviewController(GapQueryRepository gaps, MatchQueryRepository matches, ReviewService reviews,
                            ReviewDecisionRepository audit) {
        this.gaps = gaps;
        this.matches = matches;
        this.reviews = reviews;
        this.audit = audit;
    }

    @GetMapping("/api/v1/gaps")
    @Operation(summary = "List selection gaps, highest priority first",
            description = "Filters: targetSourceId, status (OPEN, CONFIRMED, DISMISSED, RESOLVED), priority (P1-P3), "
                    + "reason, category (leaf), categoryRoot (top-level reference category, as in the coverage view), "
                    + "minConfidence. Keyset pagination.")
    public PageResponse<GapResponse> list(@RequestParam(required = false) Long targetSourceId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String priority,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String categoryRoot,
                                          @RequestParam(required = false) Double minConfidence,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        return PageResponse.of(gaps.search(targetSourceId, upper(status), upper(priority), upper(reason), category,
                categoryRoot, minConfidence, page), page.limit(), GapResponse::from);
    }

    @GetMapping("/api/v1/gaps/{gapId}")
    @Operation(summary = "Get a gap with its evidence and full review history")
    public GapDetail get(@PathVariable long gapId) {
        return new GapDetail(GapResponse.from(gaps.get(gapId)), audit.forGap(gapId));
    }

    @PostMapping("/api/v1/gaps/{gapId}/decisions")
    @Operation(summary = "Confirm, dismiss, resolve or reopen a gap",
            description = "409 INVALID_TRANSITION for transitions the lifecycle forbids. Repeating the current state is "
                    + "a no-op (changed=false). Send an Idempotency-Key header to make retries safe.")
    public DecisionResponse decideGap(@PathVariable long gapId, @RequestBody DecisionRequest body,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DecisionResult r = reviews.decideGap(gapId, body.decision(), ReviewerIdentity.resolve(body.reviewer()),
                body.reason(), idempotencyKey);
        return new DecisionResponse(r, GapResponse.from(gaps.get(gapId)));
    }

    @PostMapping("/api/v1/match-candidates/{candidateId}/decisions")
    @Operation(summary = "Approve or reject a candidate match",
            description = "APPROVE links both listings to one canonical product; REJECT splits an auto-accepted pair. "
                    + "409 CANDIDATE_NOT_REVIEWABLE for superseded candidates; 409 CANDIDATE_STALE when either listing "
                    + "changed or was withdrawn after the candidate was scored (it is re-matched automatically).")
    public DecisionResponse decideMatch(@PathVariable long candidateId, @RequestBody DecisionRequest body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DecisionResult r = reviews.decideMatch(candidateId, body.decision(), ReviewerIdentity.resolve(body.reviewer()),
                body.reason(), idempotencyKey);
        return new DecisionResponse(r, MatchingController.CandidateResponse.from(matches.getCandidate(candidateId)));
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    /** @param reviewer used only when authentication is disabled (local development) */
    public record DecisionRequest(String decision, String reviewer, String reason) {
    }

    public record DecisionResponse(DecisionResult result, Object subject) {
    }

    public record GapDetail(GapResponse gap, List<ReviewDecision> history) {
    }

    public record GapResponse(long gapId, long canonicalProductId, long targetSourceId, String targetSourceCode,
                              String status, String reason, BigDecimal confidence, BigDecimal priorityScore,
                              String priority, Long bestCandidateListingId, BigDecimal bestCandidateScore,
                              @JsonRawValue String evidence, int detectionCount, Instant firstDetectedAt,
                              Instant lastDetectedAt, Long lastJobId, Instant resolvedAt, String resolution,
                              Instant updatedAt, String productTitle, String productBrand, String productCategory,
                              long referenceListingId, String referenceTitle, String referenceSourceCode,
                              String referenceGtin, String bestCandidateTitle, String bestCandidateSourceCode) {
        static GapResponse from(GapView g) {
            return new GapResponse(g.gapId(), g.canonicalProductId(), g.targetSourceId(), g.targetSourceCode(),
                    g.status(), g.reason(), g.confidence(), g.priorityScore(), g.priority(), g.bestCandidateListingId(),
                    g.bestCandidateScore(), g.evidenceJson(), g.detectionCount(), g.firstDetectedAt(),
                    g.lastDetectedAt(), g.lastJobId(), g.resolvedAt(), g.resolution(), g.updatedAt(), g.productTitle(),
                    g.productBrand(), g.productCategory(), g.referenceListingId(), g.referenceTitle(),
                    g.referenceSourceCode(), g.referenceGtin(), g.bestCandidateTitle(), g.bestCandidateSourceCode());
        }
    }
}
