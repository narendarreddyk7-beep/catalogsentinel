package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.common.ConflictException;
import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import io.catalogsentinel.core.matching.MatchingService;
import io.catalogsentinel.core.testsupport.CoreTestContext;
import io.catalogsentinel.domain.matching.RuleBasedMatcher;
import io.catalogsentinel.domain.source.SourceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.catalogsentinel.core.testsupport.Events.sync;
import static io.catalogsentinel.core.testsupport.Events.upsert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reconciliation jobs survive restarts, crashes, saturation and failures (docs/runbook.md#reconciliation-jobs).
 * Time passing is simulated by moving lease expiries and retry times into the past in SQL.
 */
class ReconciliationJobDurabilityIT {

    private static final String REF = "ref-supply";
    private final CoreTestContext ctx = new CoreTestContext();

    @BeforeEach
    void setUp() {
        ctx.reset();
        ctx.sourceService.register(REF, "Reference", SourceRole.REFERENCE, true);
        ctx.sourceService.register("mkt-0", "Market 0", SourceRole.MARKETPLACE, true);
        ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), REF, "R-1", 1,
                "Kestrel Trail KT-200 LED Camping Lantern, Green", "Kestrel", "KT-200", null, Map.of())));
        ctx.ingestionService.ingest(sync(upsert(UUID.randomUUID(), REF, "R-2", 1,
                "Vantor Brew VB-17 1.7 L Electric Kettle", "Vantor", "VB-17", null, Map.of())));
    }

    private ReconciliationJob job(long id) {
        return ctx.reconciliationService.get(id);
    }

    private void expireLease(long jobId) {
        ctx.jdbcTemplate.update("UPDATE reconciliation_jobs SET lease_expires_at = now() - interval '1 second' "
                + "WHERE job_id = ?", jobId);
    }

    private void makeRetryDue(long jobId) {
        ctx.jdbcTemplate.update("UPDATE reconciliation_jobs SET next_attempt_at = now() WHERE job_id = ?", jobId);
    }

    private ReconciliationService serviceWith(MatchingService matching) {
        return new ReconciliationService(ctx.reconciliationJobRepository, ctx.gapRepository, ctx.sourceRepository,
                matching, ctx.reviewDecisionRepository, ctx.tx, ctx.clock);
    }

    /** A matching service whose matchPending fails the first {@code failures} times (a database fault mid-run). */
    private MatchingService failingMatching(int failures) {
        AtomicInteger calls = new AtomicInteger();
        return new MatchingService(ctx.matchingRepository, ctx.matchCandidateRepository, ctx.canonicalProductRepository,
                new RuleBasedMatcher(), ctx.reviewDecisionRepository, ctx.tx, ctx.clock) {
            @Override
            public int matchPending(Long sourceId, int batchSize, int maxListings, Runnable afterBatch) {
                if (calls.incrementAndGet() <= failures) {
                    throw new TransientDataAccessResourceException("injected: connection reset during matching");
                }
                return super.matchPending(sourceId, batchSize, maxListings, afterBatch);
            }
        };
    }

    private static void await(String what, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void jobsRequestedWhileNoRunnerIsUpAreRunWhenOneStarts() {
        ReconciliationJob requested = ctx.reconciliationService.request("mkt-0", null, "api", null);
        assertThat(requested.status()).isEqualTo("PENDING");

        // The API restarts: a new poller finds the pending job in the database.
        ReconciliationJobPoller afterRestart = new ReconciliationJobPoller(ctx.reconciliationService, "api-2", 1,
                Duration.ofSeconds(1));
        assertThat(afterRestart.drain()).isEqualTo(1);

        ReconciliationJob done = job(requested.jobId());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.attempt()).isEqualTo(1);
        assertThat(done.leaseOwner()).isNull();
        assertThat(done.statsJson()).contains("productsEvaluated");
    }

    @Test
    void aCrashedRunnersJobIsClaimedAgainOnlyAfterItsLeaseExpires() {
        ReconciliationJob requested = ctx.reconciliationService.request("mkt-0", null, "api", null);
        ReconciliationJob claimed = ctx.reconciliationService.claimNext("runner-that-crashes").orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(requested.jobId());
        // ... and the process dies here without finishing.

        ReconciliationJobPoller survivor = new ReconciliationJobPoller(ctx.reconciliationService, "survivor", 1,
                Duration.ofSeconds(1));
        assertThat(survivor.drain()).as("a live lease is respected").isZero();
        assertThatThrownBy(() -> ctx.reconciliationService.request("mkt-0", null, "api", null))
                .as("the target stays reserved for the job being recovered").isInstanceOf(ConflictException.class);

        expireLease(requested.jobId());
        assertThat(survivor.drain()).isEqualTo(1);
        ReconciliationJob done = job(requested.jobId());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.attempt()).isEqualTo(2);
    }

    @Test
    void aRunnerThatLostItsLeaseCannotOverwriteTheNewOwner() {
        ReconciliationJob requested = ctx.reconciliationService.request("mkt-0", null, "api", null);
        ctx.reconciliationService.claimNext("slow-runner").orElseThrow();
        expireLease(requested.jobId());
        ReconciliationJob reclaimed = ctx.reconciliationService.claimNext("new-runner").orElseThrow();
        assertThat(reclaimed.attempt()).isEqualTo(2);

        ReconciliationJobRepository jobs = ctx.reconciliationJobRepository;
        assertThat(jobs.renewLease(requested.jobId(), "slow-runner", Duration.ofMinutes(5), Map.of())).isFalse();
        assertThat(jobs.complete(requested.jobId(), "slow-runner", Map.of())).isFalse();
        assertThat(jobs.failAttempt(requested.jobId(), "slow-runner", "late", Map.of(), Duration.ZERO, true)).isEmpty();
        assertThat(job(requested.jobId()).leaseOwner()).isEqualTo("new-runner");

        ctx.reconciliationService.runClaimed(reclaimed, "new-runner");
        assertThat(job(requested.jobId()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aFailedAttemptIsRetriedAfterABackoffWithTheErrorVisible() {
        ReconciliationService flaky = serviceWith(failingMatching(1));
        ReconciliationJob requested = flaky.request("mkt-0", null, "api", null);
        ReconciliationJobPoller poller = new ReconciliationJobPoller(flaky, "p", 1, Duration.ofSeconds(1));

        assertThat(poller.drain()).isEqualTo(1);
        ReconciliationJob afterFailure = job(requested.jobId());
        assertThat(afterFailure.status()).isEqualTo("PENDING");
        assertThat(afterFailure.attempt()).isEqualTo(1);
        assertThat(afterFailure.errorMessage()).contains("connection reset during matching");
        assertThat(afterFailure.nextAttemptAt()).isAfter(Instant.now().plusSeconds(20));
        assertThat(poller.drain()).as("not before the backoff").isZero();

        makeRetryDue(requested.jobId());
        assertThat(poller.drain()).isEqualTo(1);
        ReconciliationJob done = job(requested.jobId());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.attempt()).isEqualTo(2);
        assertThat(done.errorMessage()).isNull();
    }

    @Test
    void attemptsAreBoundedAndTheFinalFailureFreesTheTarget() {
        ReconciliationService broken = serviceWith(failingMatching(Integer.MAX_VALUE));
        ReconciliationJob requested = broken.request("mkt-0", null, "api", null);
        ReconciliationJobPoller poller = new ReconciliationJobPoller(broken, "p", 1, Duration.ofSeconds(1));
        for (int attempt = 1; attempt <= 3; attempt++) {
            makeRetryDue(requested.jobId());
            assertThat(poller.drain()).isEqualTo(1);
        }
        makeRetryDue(requested.jobId());
        assertThat(poller.drain()).isZero();

        ReconciliationJob failed = job(requested.jobId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.attempt()).isEqualTo(3);
        assertThat(failed.errorMessage()).contains("injected");
        assertThat(ctx.reconciliationService.request("mkt-0", null, "api", null).status()).isEqualTo("PENDING");
    }

    @Test
    void aJobWhoseRunnerDiedOnTheLastAttemptFailsVisiblyAndFreesTheTarget() {
        ReconciliationJob requested = ctx.reconciliationService.request("mkt-0", null, "api", null);
        ctx.jdbcTemplate.update("UPDATE reconciliation_jobs SET max_attempts = 1 WHERE job_id = ?", requested.jobId());
        ctx.reconciliationService.claimNext("dies").orElseThrow();
        expireLease(requested.jobId());

        ReconciliationJob next = ctx.reconciliationService.request("mkt-0", null, "api", null);

        assertThat(next.jobId()).isNotEqualTo(requested.jobId());
        ReconciliationJob failed = job(requested.jobId());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorMessage()).contains("lease expired during attempt 1 of 1").contains("dies");
    }

    @Test
    void aSaturatedRunnerLeavesJobsQueuedInTheDatabaseInsteadOfDroppingThem() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            ctx.sourceService.register("mkt-" + i, "Market " + i, SourceRole.MARKETPLACE, true);
            ids.add(ctx.reconciliationService.request("mkt-" + i, null, "api", null).jobId());
        }
        ReconciliationJobPoller oneWorker = new ReconciliationJobPoller(ctx.reconciliationService, "single", 1,
                Duration.ofMillis(100));
        oneWorker.start();
        try {
            for (long id : ids) {
                oneWorker.wakeUp(); // every request nudges the poller, as the API does
            }
            await("all jobs succeeded", () -> ids.stream().allMatch(id -> job(id).status().equals("SUCCEEDED")));
        } finally {
            assertThat(oneWorker.stop(Duration.ofSeconds(30))).isTrue();
        }
        assertThat(ids).allSatisfy(id -> assertThat(job(id).attempt()).isEqualTo(1));
    }

    @Test
    void twoPollersOnOneDatabaseRunEachAttemptExactlyOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ctx.sourceService.register("mkt-" + i, "Market " + i, SourceRole.MARKETPLACE, true);
            ids.add(ctx.reconciliationService.request("mkt-" + i, null, "api", null).jobId());
        }
        ReconciliationJobPoller a = new ReconciliationJobPoller(ctx.reconciliationService, "instance-a", 2,
                Duration.ofMillis(50));
        ReconciliationJobPoller b = new ReconciliationJobPoller(ctx.reconciliationService, "instance-b", 2,
                Duration.ofMillis(50));
        a.start();
        b.start();
        try {
            await("all jobs succeeded", () -> ids.stream().allMatch(id -> job(id).status().equals("SUCCEEDED")));
        } finally {
            a.stop(Duration.ofSeconds(30));
            b.stop(Duration.ofSeconds(30));
        }
        assertThat(ids).allSatisfy(id -> {
            assertThat(job(id).attempt()).as("claimed exactly once").isEqualTo(1);
            assertThat(job(id).leaseOwner()).isNull();
        });
    }
}
