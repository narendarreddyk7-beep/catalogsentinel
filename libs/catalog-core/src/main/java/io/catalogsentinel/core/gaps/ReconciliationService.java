package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.common.NotFoundException;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.gaps.GapRepository.BestCandidateRow;
import io.catalogsentinel.core.gaps.GapRepository.GapRow;
import io.catalogsentinel.core.gaps.GapRepository.MergedProductGap;
import io.catalogsentinel.core.gaps.GapRepository.ReferenceProduct;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.matching.MatchingService;
import io.catalogsentinel.core.review.ReviewDecisionRepository;
import io.catalogsentinel.core.source.CatalogSource;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.gap.GapAssessor;
import io.catalogsentinel.domain.gap.GapAssessor.Assessment;
import io.catalogsentinel.domain.gap.GapStatus;
import io.catalogsentinel.domain.source.SourceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles a target marketplace against reference catalogs and maintains selection gaps.
 *
 * <p>Steps of a run:
 * <ol>
 *   <li>Bring matching up to date for listings that are unlinked or changed since they were linked, then merge
 *       duplicate canonical identities. Live gaps on products that were merged away follow their product to the
 *       surviving one, keeping their status and review history (docs/adr/0014-gaps-follow-product-merges.md).</li>
 *   <li>For every canonical product with an eligible reference listing (ACTIVE, from an ENABLED reference
 *       source, and from the selected reference source if the run has one; keyset batches): if the target carries
 *       it, resolve any open gap; otherwise assess the gap from the closest current candidate and upsert it.
 *       Coverage numerator and denominator and the synthetic demand use the same eligibility.</li>
 *   <li>Resolve gaps whose product was merged away, lost all reference listings, or is listed only by disabled
 *       reference sources (eligibility for this step is global, so a scoped run never resolves gaps that other
 *       enabled sources still justify).</li>
 * </ol>
 * Idempotent: a second run over unchanged data leaves the same set of gaps (only detection counters move).
 *
 * <p>Execution is durable (docs/runbook.md#reconciliation-jobs): a request only records a PENDING job. Runners
 * claim jobs from the database with a lease ({@link #claimNext}, used by {@link ReconciliationJobPoller}) and
 * renew it as they progress; a crashed runner's lease expires and the job is claimed again as its next attempt.
 * Retryable failures go back to PENDING after an exponential backoff, at most {@code max_attempts} times; a
 * permanent failure or the last attempt leaves it FAILED with the error recorded. One active job per target is
 * enforced by a partial unique index.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    public static final String SYSTEM_REVIEWER = "system:reconciliation";
    static final int BATCH = 200;
    /** Lease granted per claim and per renewal; renewals happen after every batch of work. */
    public static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration RETRY_BASE = Duration.ofSeconds(30);
    static final Duration RETRY_CAP = Duration.ofMinutes(10);

    private final ReconciliationJobRepository jobs;
    private final GapRepository gaps;
    private final SourceRepository sources;
    private final MatchingService matching;
    private final ReviewDecisionRepository audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ReconciliationService(ReconciliationJobRepository jobs, GapRepository gaps, SourceRepository sources,
                                 MatchingService matching, ReviewDecisionRepository audit, TransactionTemplate tx,
                                 Clock clock) {
        this.jobs = jobs;
        this.gaps = gaps;
        this.sources = sources;
        this.matching = matching;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Creates a PENDING job (does not run it). Same idempotency key returns the same job; a concurrent request
     * for the same target is rejected with {@code RECONCILIATION_IN_PROGRESS}.
     */
    public ReconciliationJob request(String targetCode, String referenceCode, String requestedBy, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<ReconciliationJob> existing = jobs.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        CatalogSource target = sources.findByCode(targetCode)
                .orElseThrow(() -> new NotFoundException("source", targetCode));
        if (target.role() != SourceRole.MARKETPLACE) {
            throw new UnprocessableException("TARGET_NOT_MARKETPLACE", "target '" + targetCode + "' is not a MARKETPLACE source");
        }
        Long referenceId = null;
        if (referenceCode != null && !referenceCode.isBlank()) {
            CatalogSource reference = sources.findByCode(referenceCode)
                    .orElseThrow(() -> new NotFoundException("source", referenceCode));
            if (reference.role() != SourceRole.REFERENCE) {
                throw new UnprocessableException("NOT_A_REFERENCE_SOURCE", "'" + referenceCode + "' is not a REFERENCE source");
            }
            if (!reference.enabled()) {
                throw new UnprocessableException("REFERENCE_SOURCE_DISABLED", "reference source '" + referenceCode
                        + "' is disabled; enable it or reconcile against all enabled reference sources");
            }
            referenceId = reference.sourceId();
        }
        int exhausted = jobs.failExhaustedLeases();
        if (exhausted > 0) {
            log.warn("failed {} reconciliation job(s) whose runner died on its last attempt", exhausted);
        }
        Long finalReferenceId = referenceId;
        Optional<Long> jobId = jobs.insertPending(target.sourceId(), finalReferenceId,
                requestedBy == null ? "anonymous" : requestedBy, idempotencyKey);
        if (jobId.isEmpty()) {
            if (idempotencyKey != null) {
                Optional<ReconciliationJob> raced = jobs.findByIdempotencyKey(idempotencyKey);
                if (raced.isPresent()) {
                    return raced.get();
                }
            }
            ReconciliationJob active = jobs.findActive(target.sourceId()).orElse(null);
            throw new ConflictException("RECONCILIATION_IN_PROGRESS", "a reconciliation for '" + targetCode
                    + "' is already queued or running" + (active == null ? "" : " (job " + active.jobId() + ")"));
        }
        return jobs.find(jobId.get()).orElseThrow();
    }

    public ReconciliationJob get(long jobId) {
        return jobs.find(jobId).orElseThrow(() -> new NotFoundException("reconciliation job", jobId));
    }

    /**
     * Claims the job if it is claimable now and runs it on the calling thread (tests, tooling). Returns its state
     * afterwards; a job that is not claimable (already running elsewhere, finished, or waiting for its retry
     * backoff) is returned unchanged.
     */
    public ReconciliationJob run(long jobId) {
        String owner = "direct:" + UUID.randomUUID();
        jobs.claim(jobId, owner, LEASE).ifPresent(job -> runClaimed(job, owner));
        return get(jobId);
    }

    /**
     * Claims the oldest claimable job for {@code owner}: a due PENDING job or one whose runner's lease expired.
     * First fails jobs whose lease expired on their last attempt, so they never block their target.
     */
    public Optional<ReconciliationJob> claimNext(String owner) {
        int exhausted = jobs.failExhaustedLeases();
        if (exhausted > 0) {
            log.warn("failed {} reconciliation job(s) whose runner died on its last attempt", exhausted);
        }
        return jobs.claim(null, owner, LEASE);
    }

    /**
     * Runs a job whose lease {@code owner} holds, renewing the lease after every batch. Records success, a retry
     * (retryable failure with attempts left) or a final failure. If the lease is lost to another runner, stops
     * without recording anything: the other runner owns the outcome. Never throws.
     */
    public void runClaimed(ReconciliationJob job, String owner) {
        long jobId = job.jobId();
        Map<String, Long> stats = new LinkedHashMap<>();
        Runnable renew = () -> {
            if (!jobs.renewLease(jobId, owner, LEASE, stats)) {
                throw new LeaseLostException(jobId, owner);
            }
        };
        try {
            int referenceTotal = gaps.referenceSourceScope(job.referenceSourceId());
            if (job.referenceSourceId() != null && referenceTotal == 0) {
                throw new PermanentJobFailure("REFERENCE_SOURCE_DISABLED: reference source "
                        + job.referenceSourceCode() + " was disabled after the run was requested");
            }
            stats.put("listingsMatched", (long) matching.matchPending(null, 200, Integer.MAX_VALUE, renew));
            stats.put("identitiesMerged", (long) matching.mergeDuplicateIdentities(10_000));
            followMerges(job, stats);
            renew.run();
            long after = 0;
            while (true) {
                long cursor = after;
                List<ReferenceProduct> batch = gaps.referenceProducts(job.targetSourceId(), job.referenceSourceId(),
                        cursor, BATCH);
                if (batch.isEmpty()) {
                    break;
                }
                tx.execute(s -> {
                    batch.forEach(p -> evaluate(job, p, referenceTotal, stats));
                    return null;
                });
                after = batch.get(batch.size() - 1).canonicalProductId();
                renew.run();
            }
            followMerges(job, stats); // products merged by the worker while this run was evaluating
            tx.execute(s -> {
                resolveInvalidated(job, stats);
                return null;
            });
            if (jobs.complete(jobId, owner, stats)) {
                log.info("reconciliation job {} for {} succeeded on attempt {}: {}", jobId, job.targetSourceCode(),
                        job.attempt(), stats);
            } else {
                log.warn("reconciliation job {} finished its work but no longer held its lease; outcome left to the "
                        + "current owner", jobId);
            }
        } catch (LeaseLostException e) {
            log.warn("reconciliation job {} attempt {}: lease lost to another runner; stopping", jobId, job.attempt());
        } catch (RuntimeException e) {
            boolean permanent = e instanceof PermanentJobFailure || e instanceof DomainValidationException
                    || e instanceof UnprocessableException || e instanceof NotFoundException;
            Duration backoff = backoff(job.attempt());
            String error = e instanceof PermanentJobFailure ? e.getMessage()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            Optional<String> status = jobs.failAttempt(jobId, owner, error, stats, backoff, permanent);
            if (status.map("PENDING"::equals).orElse(false)) {
                log.warn("reconciliation job {} attempt {} of {} failed, retrying in {}s: {}", jobId, job.attempt(),
                        job.maxAttempts(), backoff.toSeconds(), error, e);
            } else {
                log.error("reconciliation job {} failed on attempt {} of {} ({}): {}", jobId, job.attempt(),
                        job.maxAttempts(), permanent ? "not retryable" : "no attempts left", error, e);
            }
        }
    }

    static Duration backoff(int attempt) {
        long seconds = RETRY_BASE.toSeconds() << Math.min(Math.max(attempt - 1, 0), 10);
        return Duration.ofSeconds(Math.min(seconds, RETRY_CAP.toSeconds()));
    }

    /** The runner's lease was taken over by another runner (it expired); this attempt must stop writing. */
    static final class LeaseLostException extends RuntimeException {
        LeaseLostException(long jobId, String owner) {
            super("lease on reconciliation job " + jobId + " no longer held by " + owner);
        }
    }

    /** A failure that retrying cannot fix (for example a disabled reference source). */
    static final class PermanentJobFailure extends RuntimeException {
        PermanentJobFailure(String message) {
            super(message);
        }
    }

    private void evaluate(ReconciliationJob job, ReferenceProduct product, int referenceTotal, Map<String, Long> stats) {
        stats.merge("productsEvaluated", 1L, Long::sum);
        Instant now = clock.instant();
        if (product.coveringListingId() != null) {
            stats.merge("productsCarried", 1L, Long::sum);
            gaps.findResolvableGap(product.canonicalProductId(), job.targetSourceId()).ifPresent(gapId -> {
                resolve(gapId, GapStatus.OPEN, "AUTO_MATCH_FOUND",
                        "target now carries the product (listing " + product.coveringListingId() + ")", now);
                stats.merge("gapsResolved", 1L, Long::sum);
            });
            return;
        }
        Optional<BestCandidateRow> best = gaps.bestCandidate(product.referenceListingIds(), job.targetSourceId());
        Assessment a = GapAssessor.assess(new GapAssessor.Input(
                best.map(b -> new GapAssessor.BestCandidate(b.targetListingId(), b.score(), b.decision(),
                        b.reviewStatus(), b.evidenceCodes())).orElse(null),
                product.referenceSources(), referenceTotal, product.syntheticDemand()));

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("assessor", GapAssessor.VERSION);
        evidence.put("reason", a.reason().name());
        evidence.put("explanation", a.explanation());
        evidence.put("referenceListings", product.referenceListingIds());
        evidence.put("referenceCoverage", Map.of("sources", product.referenceSources(), "of", referenceTotal));
        evidence.put("demand", Map.of("value", a.demand(), "source", a.demandKnown() ? "SYNTHETIC" : "UNKNOWN"));
        best.ifPresent(b -> evidence.put("bestCandidate", Map.of("candidateId", b.candidateId(),
                "listingId", b.targetListingId(), "score", b.score(), "decision", b.decision(),
                "reviewStatus", b.reviewStatus(), "evidenceCodes", b.evidenceCodes().stream().sorted().toList())));

        GapRepository.UpsertResult r = gaps.upsert(product.canonicalProductId(), job.targetSourceId(),
                product.referenceListingIds().get(0), a.reason().name(), a.confidence(), a.priorityScore(), a.priority(),
                best.map(BestCandidateRow::targetListingId).orElse(null), best.map(BestCandidateRow::score).orElse(null),
                evidence, job.jobId());
        if (r.inserted()) {
            stats.merge("gapsOpened", 1L, Long::sum);
        } else if (r.status() == GapStatus.DISMISSED) {
            stats.merge("gapsSuppressedByDismissal", 1L, Long::sum);
        } else {
            stats.merge("gapsRefreshed", 1L, Long::sum);
        }
    }

    /**
     * Gaps follow their product through merges (docs/adr/0014-gaps-follow-product-merges.md). A live gap on a
     * merged-away product moves to the surviving product with its id, status and review history, so a confirmation
     * or dismissal keeps applying and evaluation then treats it like the survivor's own gap. If the survivor already
     * has a live gap for this marketplace, that gap is kept and the merged product's gap is resolved
     * ({@code MERGED_PRODUCT}) with the conflict recorded in its audit trail; no decision is applied to another gap.
     */
    private void followMerges(ReconciliationJob job, Map<String, Long> stats) {
        tx.execute(s -> {
            for (MergedProductGap m : gaps.liveGapsOnMergedProducts(job.targetSourceId())) {
                Optional<GapRow> locked = gaps.lock(m.gapId());
                if (locked.isEmpty() || locked.get().status() == GapStatus.RESOLVED) {
                    continue;
                }
                Optional<GapRow> survivorGap = gaps.findLiveGap(m.survivorProductId(), job.targetSourceId());
                if (survivorGap.isEmpty()) {
                    gaps.moveToProduct(m.gapId(), m.survivorProductId());
                    stats.merge("gapsFollowedMerge", 1L, Long::sum);
                    log.info("gap {} ({}) follows product {} into product {}", m.gapId(), locked.get().status(),
                            m.mergedProductId(), m.survivorProductId());
                    continue;
                }
                GapStatus current = locked.get().status();
                String reason = "product " + m.mergedProductId() + " was merged into product " + m.survivorProductId()
                        + ", which already has gap " + survivorGap.get().gapId() + " for this marketplace; this gap's"
                        + " review decisions were not carried over";
                gaps.updateStatus(m.gapId(), GapStatus.RESOLVED, "MERGED_PRODUCT", clock.instant());
                audit.append(null, m.gapId(), "RESOLVE", current.name(), GapStatus.RESOLVED.name(), SYSTEM_REVIEWER,
                        "MERGED_PRODUCT: " + reason, null, clock.instant());
                stats.merge("mergedGapConflicts", 1L, Long::sum);
                stats.merge("gapsResolved", 1L, Long::sum);
                log.warn("gap {} ({}) resolved: {}", m.gapId(), current, reason);
            }
            return null;
        });
    }

    private void resolveInvalidated(ReconciliationJob job, Map<String, Long> stats) {
        for (Map.Entry<Long, String> e : gaps.invalidatedGaps(job.targetSourceId())) {
            gaps.lock(e.getKey()).ifPresent(row -> {
                resolve(row.gapId(), row.status(), e.getValue(), "product no longer qualifies as a gap", clock.instant());
                stats.merge("gapsResolved", 1L, Long::sum);
            });
        }
    }

    private void resolve(long gapId, GapStatus previous, String resolution, String reason, Instant now) {
        GapStatus current = gaps.lock(gapId).map(GapRepository.GapRow::status).orElse(previous);
        if (current == GapStatus.RESOLVED || current == GapStatus.DISMISSED) {
            return;
        }
        gaps.updateStatus(gapId, GapStatus.RESOLVED, resolution, now);
        audit.append(null, gapId, "RESOLVE", current.name(), GapStatus.RESOLVED.name(), SYSTEM_REVIEWER,
                resolution + ": " + reason, null, now);
    }
}
