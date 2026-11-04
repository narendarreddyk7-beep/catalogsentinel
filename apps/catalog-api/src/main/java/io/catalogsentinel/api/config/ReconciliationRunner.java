package io.catalogsentinel.api.config;

import io.catalogsentinel.core.gaps.ReconciliationJobPoller;
import io.catalogsentinel.core.gaps.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

/**
 * Hosts the database-backed reconciliation poller in the API process (docs/runbook.md#reconciliation-jobs).
 * Requests only record PENDING jobs and nudge the poller; jobs are claimed from the database with leases, so
 * executor saturation, restarts and crashes never lose a job, and several API instances can run side by side.
 * On shutdown running jobs get a grace period; unfinished ones are resumed by another instance once their lease
 * expires.
 */
@Component
public class ReconciliationRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);
    private final ReconciliationJobPoller poller;
    private final boolean enabled;
    private volatile boolean running;

    public ReconciliationRunner(ReconciliationService reconciliation, CatalogApiProperties props) {
        CatalogApiProperties.Reconciliation settings = props.reconciliation();
        int threads = settings == null ? 2 : Math.max(1, settings.workerThreads());
        Duration interval = settings == null || settings.pollInterval() == null ? Duration.ofSeconds(5)
                : settings.pollInterval();
        this.enabled = settings == null || settings.pollerEnabled() == null || settings.pollerEnabled();
        this.poller = new ReconciliationJobPoller(reconciliation, ownerId(), threads, interval);
    }

    /** A job was requested: look for work now instead of at the next poll. */
    public void submit(long jobId) {
        log.debug("reconciliation job {} requested; waking the poller", jobId);
        poller.wakeUp();
    }

    @Override
    public void start() {
        if (enabled) {
            poller.start();
        } else {
            log.info("reconciliation poller disabled in this instance (catalogsentinel.reconciliation.poller-enabled)");
        }
        running = true; // Spring starts a SmartLifecycle bean only while isRunning() is false
    }

    @Override
    public void stop() {
        try {
            poller.stop(Duration.ofSeconds(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static String ownerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return "api@" + host + "/" + ManagementFactory.getRuntimeMXBean().getName().split("@")[0] + "/"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
