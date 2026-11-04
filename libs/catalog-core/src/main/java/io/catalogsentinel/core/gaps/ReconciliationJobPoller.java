package io.catalogsentinel.core.gaps;

import io.catalogsentinel.core.gaps.ReconciliationJobRepository.ReconciliationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Database-backed runner for reconciliation jobs (docs/runbook.md#reconciliation-jobs). A scheduler thread
 * claims jobs only while a worker is free, every {@code pollInterval} and immediately after {@link #wakeUp()},
 * and runs each on a bounded pool. Nothing waits in an in-memory queue that could reject or lose it: a job that
 * cannot run yet stays claimable in the database, by this instance or any other. Claims use
 * {@code FOR UPDATE SKIP LOCKED} and leases, so any number of pollers can run against one database, a restarted
 * process picks up pending work, and a crashed one's jobs are claimed again when their leases expire.
 */
public final class ReconciliationJobPoller {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJobPoller.class);

    private final ReconciliationService service;
    private final String owner;
    private final int workers;
    private final Duration pollInterval;
    private final Semaphore capacity;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ExecutorService pool;

    public ReconciliationJobPoller(ReconciliationService service, String owner, int workers, Duration pollInterval) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be >= 1");
        }
        this.service = service;
        this.owner = owner;
        this.workers = workers;
        this.pollInterval = pollInterval;
        this.capacity = new Semaphore(workers);
    }

    public String owner() {
        return owner;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        pool = Executors.newFixedThreadPool(workers, threads("reconciliation-runner-"));
        scheduler = Executors.newSingleThreadScheduledExecutor(threads("reconciliation-poller-"));
        running = true;
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("reconciliation poller {} started: {} worker(s), polling every {} ms", owner, workers,
                pollInterval.toMillis());
    }

    /** Polls now instead of at the next interval (called after a job is requested). */
    public void wakeUp() {
        ScheduledExecutorService s = scheduler;
        if (running && s != null) {
            try {
                s.execute(this::pollSafely);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // stopping; the job stays in the database for the next poller
            }
        }
    }

    /**
     * Stops claiming and waits up to {@code grace} for running jobs. Jobs still running afterwards are not
     * interrupted: their leases expire and another poller resumes them as their next attempt.
     */
    public synchronized boolean stop(Duration grace) throws InterruptedException {
        if (!running) {
            return true;
        }
        running = false;
        scheduler.shutdownNow();
        pool.shutdown();
        boolean drained = pool.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
        log.info("reconciliation poller {} stopped (all jobs finished: {})", owner, drained);
        return drained;
    }

    /**
     * Synchronous mode for tests and tooling: claims and runs claimable jobs on the calling thread until none is
     * left. Returns how many attempts ran.
     */
    public int drain() {
        int ran = 0;
        Optional<ReconciliationJob> job;
        while ((job = service.claimNext(owner)).isPresent()) {
            runLogged(job.get());
            ran++;
        }
        return ran;
    }

    private void pollSafely() {
        try {
            while (running && capacity.tryAcquire()) {
                Optional<ReconciliationJob> job;
                try {
                    job = service.claimNext(owner);
                } catch (RuntimeException e) {
                    capacity.release();
                    throw e;
                }
                if (job.isEmpty()) {
                    capacity.release();
                    return;
                }
                ReconciliationJob claimed = job.get();
                try {
                    pool.execute(() -> {
                        try {
                            runLogged(claimed);
                        } finally {
                            capacity.release();
                            wakeUp(); // a worker is free: look for more work now
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    capacity.release(); // stopping: the lease expires and the job is claimed again elsewhere
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("reconciliation poll failed (will retry in {} ms): {}", pollInterval.toMillis(), e.toString());
        }
    }

    private void runLogged(ReconciliationJob job) {
        MDC.put("reconciliationJobId", Long.toString(job.jobId()));
        try {
            log.info("reconciliation job {} for {} claimed by {} (attempt {} of {})", job.jobId(),
                    job.targetSourceCode(), owner, job.attempt(), job.maxAttempts());
            service.runClaimed(job, owner);
        } catch (RuntimeException e) {
            log.error("reconciliation job {} runner crashed; its lease will expire and it will be retried",
                    job.jobId(), e);
        } finally {
            MDC.remove("reconciliationJobId");
        }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
