package io.catalogsentinel.api.web;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.catalogsentinel.core.common.PageRequest;
import io.catalogsentinel.core.matching.MatchQueryRepository;
import io.catalogsentinel.core.matching.MatchQueryRepository.CandidateView;
import io.catalogsentinel.core.matching.MatchQueryRepository.CanonicalProductView;
import io.catalogsentinel.core.matching.MatchQueryRepository.LinkedListingView;
import io.catalogsentinel.core.matching.MatchQueryRepository.ListingSide;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@RestController
@Tag(name = "Matching", description = "Scored candidate pairs, evidence and canonical products")
public class MatchingController {

    private final MatchQueryRepository queries;

    public MatchingController(MatchQueryRepository queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/match-candidates")
    @Operation(summary = "List match candidates (review queue)",
            description = "Filters: reviewStatus (PENDING, APPROVED, REJECTED, AUTO_ACCEPTED, NOT_APPLICABLE, "
                    + "SUPERSEDED), decision (AUTO_MATCH, REVIEW, NO_MATCH), minScore, sourceId, listingId. Highest "
                    + "score first; keyset pagination.")
    public PageResponse<CandidateResponse> candidates(@RequestParam(required = false) String reviewStatus,
                                                      @RequestParam(required = false) String decision,
                                                      @RequestParam(required = false) Double minScore,
                                                      @RequestParam(required = false) Long sourceId,
                                                      @RequestParam(required = false) Long listingId,
                                                      @RequestParam(required = false) String cursor,
                                                      @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        return PageResponse.of(queries.searchCandidates(upper(reviewStatus), upper(decision), minScore, sourceId,
                listingId, page), page.limit(), CandidateResponse::from);
    }

    @GetMapping("/api/v1/match-candidates/{candidateId}")
    @Operation(summary = "Get a candidate pair with both listings side by side, feature scores and evidence")
    public CandidateResponse candidate(@PathVariable long candidateId) {
        return CandidateResponse.from(queries.getCandidate(candidateId));
    }

    @GetMapping("/api/v1/canonical-products")
    @Operation(summary = "Search canonical products", description = "Filters: q (title text), brand, category")
    public PageResponse<CanonicalProductView> products(@RequestParam(required = false) String q,
                                                       @RequestParam(required = false) String brand,
                                                       @RequestParam(required = false) String category,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(defaultValue = "50") int limit) {
        PageRequest page = new PageRequest(cursor, limit);
        return PageResponse.of(queries.searchProducts(q, brand, category, page), page.limit());
    }

    @GetMapping("/api/v1/canonical-products/{id}")
    @Operation(summary = "Get a canonical product with every linked listing and how it was linked")
    public CanonicalProductDetail product(@PathVariable long id) {
        return new CanonicalProductDetail(queries.getProduct(id), queries.linkedListings(id));
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(java.util.Locale.ROOT);
    }

    public record CanonicalProductDetail(CanonicalProductView product, List<LinkedListingView> listings) {
    }

    /** Feature scores and evidence are embedded as JSON objects exactly as stored. */
    public record CandidateResponse(long candidateId, BigDecimal score, String decision, String reviewStatus,
                                    String matcherVersion, String retrievalMethod,
                                    @JsonRawValue String featureScores, @JsonRawValue String evidence,
                                    String reviewedBy, Instant reviewedAt, Instant createdAt, Instant updatedAt,
                                    ListingSide left, ListingSide right) {
        static CandidateResponse from(CandidateView v) {
            return new CandidateResponse(v.candidateId(), v.score(), v.decision(), v.reviewStatus(), v.matcherVersion(),
                    v.retrievalMethod(), v.featureScoresJson(), v.evidenceJson(), v.reviewedBy(), v.reviewedAt(),
                    v.createdAt(), v.updatedAt(), v.left(), v.right());
        }
    }
}
