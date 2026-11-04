package io.catalogsentinel.core.messaging;

import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.messaging.MessageHandler.Ack;
import io.catalogsentinel.core.messaging.MessageHandler.DeadLetter;
import io.catalogsentinel.core.messaging.MessageHandler.Outcome;
import io.catalogsentinel.core.messaging.MessageHandler.Retry;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * At-least-once SQS consumer.
 *
 * <ul>
 *   <li><b>Concurrency</b>: one poller; up to {@code concurrency} messages processed in parallel (virtual
 *       threads). Never receives more messages than it has free permits, so nothing sits in memory invisible.</li>
 *   <li><b>Visibility</b>: received messages stay invisible for {@code visibilityTimeout}; a heartbeat extends it
 *       while a slow handler is still working, so a message is not redelivered mid-processing.</li>
 *   <li><b>Retries</b>: a retryable failure re-schedules the message with {@code ChangeMessageVisibility} using
 *       exponential backoff with full jitter. After {@code maxAttempts} receives it is dead-lettered explicitly.
 *       The queue's own redrive policy (maxReceiveCount above maxAttempts) is the safety net if this process
 *       crashes before it can do so.</li>
 *   <li><b>Poison messages</b>: permanent failures go to the DLQ immediately with a failure reason attribute.</li>
 *   <li><b>Dependency outages</b>: after consecutive outage-type failures polling pauses and resumes only when a
 *       health probe passes, so an outage does not burn receive counts and flood the DLQ.</li>
 *   <li><b>Shutdown</b>: stops polling, waits for in-flight messages up to a deadline; anything unfinished becomes
 *       visible again after its timeout and is redelivered (processing is idempotent).</li>
 * </ul>
 */
public final class QueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(QueueConsumer.class);

    /** Consumer configuration. */
    public record Settings(String queue, String deadLetterQueue, int concurrency, int maxAttempts,
                           Duration waitTime, Duration visibilityTimeout, BackoffPolicy backoff,
                           int outageThreshold, Duration outagePause, Duration shutdownGrace) {
        public Settings {
            Objects.requireNonNull(queue);
            Objects.requireNonNull(deadLetterQueue);
            if (concurrency < 1 || maxAttempts < 1 || outageThreshold < 1) {
                throw new IllegalArgumentException("concurrency, maxAttempts and outageThreshold must be >= 1");
            }
        }
    }

    private final QueueClient queue;
    private final MessageHandler handler;
    private final Settings settings;
    private final ConsumerMetrics metrics;
    private final BooleanSupplier dependencyHealthy;
    private final Semaphore permits;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger consecutiveOutageFailures = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private ExecutorService workers;
    private ScheduledExecutorService heartbeats;
    private Thread poller;

    public QueueConsumer(QueueClient queue, MessageHandler handler, Settings settings, ConsumerMetrics metrics,
                         BooleanSupplier dependencyHealthy) {
        this.queue = queue;
        this.handler = handler;
        this.settings = settings;
        this.metrics = metrics;
        this.dependencyHealthy = dependencyHealthy;
        this.permits = new Semaphore(settings.concurrency());
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "visibility-heartbeat-" + settings.queue());
            t.setDaemon(true);
            return t;
        });
        poller = Thread.ofPlatform().name("poller-" + settings.queue()).daemon(true).start(this::pollLoop);
        log.info("consumer started queue={} concurrency={} maxAttempts={}", settings.queue(), settings.concurrency(),
                settings.maxAttempts());
    }

    /** Stops polling and waits for in-flight messages; returns true if all finished within the grace period. */
    public synchronized boolean stop() {
        if (!running.compareAndSet(true, false)) {
            return true;
        }
        try {
            poller.join(settings.waitTime().plusSeconds(5).toMillis());
            workers.shutdown();
            boolean drained = workers.awaitTermination(settings.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS);
            heartbeats.shutdownNow();
            log.info("consumer stopped queue={} drained={} stillInFlight={}", settings.queue(), drained, inFlight.get());
            return drained;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                if (consecutiveOutageFailures.get() >= settings.outageThreshold()) {
                    pauseUntilHealthy();
                    continue;
                }
                permits.acquire();
                int available = 1 + permits.drainPermits();
                int batch = Math.min(10, available);
                permits.release(available - batch);
                List<ReceivedMessage> messages;
                try {
                    messages = queue.receive(settings.queue(), batch, settings.waitTime(), settings.visibilityTimeout());
                } catch (RuntimeException e) {
                    permits.release(batch);
                    log.warn("receive failed queue={}: {}", settings.queue(), e.getMessage());
                    sleep(Duration.ofSeconds(1));
                    continue;
                }
                permits.release(batch - messages.size());
                metrics.received(settings.queue(), messages.size());
                for (ReceivedMessage m : messages) {
                    inFlight.incrementAndGet();
                    workers.execute(() -> {
                        try {
                            process(m);
                        } finally {
                            inFlight.decrementAndGet();
                            permits.release();
                        }
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("unexpected error in poll loop queue={}", settings.queue(), e);
                sleep(Duration.ofSeconds(1));
            }
        }
    }

    private void pauseUntilHealthy() {
        Duration pause = settings.outagePause();
        log.warn("pausing consumption of {} for {} after {} consecutive dependency failures", settings.queue(), pause,
                consecutiveOutageFailures.get());
        metrics.paused(settings.queue(), pause);
        sleep(pause);
        boolean healthy;
        try {
            healthy = dependencyHealthy.getAsBoolean();
        } catch (RuntimeException e) {
            healthy = false;
        }
        if (healthy) {
            log.info("dependency healthy again; resuming {}", settings.queue());
            consecutiveOutageFailures.set(0);
        }
    }

    /**
     * Processes one message synchronously, including retry/dead-letter handling. Public so that tests and
     * operational tooling can drive the consumer deterministically without the polling thread.
     */
    public String process(ReceivedMessage m) {
        long start = System.nanoTime();
        Map<String, String> attrs = m.attributes();
        MDC.put("messageId", m.messageId());
        if (attrs.containsKey(MessageAttributes.CORRELATION_ID)) {
            MDC.put("correlationId", attrs.get(MessageAttributes.CORRELATION_ID));
        }
        if (attrs.containsKey(MessageAttributes.EVENT_ID)) {
            MDC.put("eventId", attrs.get(MessageAttributes.EVENT_ID));
        }
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(m);
        String result;
        try {
            Outcome outcome;
            try {
                outcome = handler.handle(m);
                consecutiveOutageFailures.set(0);
            } catch (RuntimeException e) {
                if (FailureClassifier.isDependencyOutage(e)) {
                    consecutiveOutageFailures.incrementAndGet();
                }
                outcome = FailureClassifier.isTransient(e)
                        ? MessageHandler.retry(e.getClass().getSimpleName() + ": " + e.getMessage())
                        : MessageHandler.deadLetter(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            result = apply(m, outcome);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            MDC.remove("messageId");
            MDC.remove("correlationId");
            MDC.remove("eventId");
        }
        metrics.completed(settings.queue(), result, Duration.ofNanos(System.nanoTime() - start));
        return result;
    }

    /** Receives one batch and processes it synchronously (deterministic mode for tests and tooling). */
    public int pollOnce() {
        List<ReceivedMessage> messages = queue.receive(settings.queue(), Math.min(10, settings.concurrency()),
                Duration.ZERO, settings.visibilityTimeout());
        metrics.received(settings.queue(), messages.size());
        messages.forEach(this::process);
        return messages.size();
    }

    private String apply(ReceivedMessage m, Outcome outcome) {
        return switch (outcome) {
            case Ack ack -> {
                queue.delete(settings.queue(), List.of(m.receiptHandle()));
                yield "ack";
            }
            case Retry retry -> {
                if (m.receiveCount() >= settings.maxAttempts()) {
                    yield deadLetter(m, "retries exhausted after " + m.receiveCount() + " attempts: " + retry.reason());
                }
                Duration delay = settings.backoff().delay(m.receiveCount());
                log.warn("retrying message queue={} attempt={} delayMs={} reason=\"{}\"", settings.queue(),
                        m.receiveCount(), delay.toMillis(), retry.reason());
                try {
                    queue.changeVisibility(settings.queue(), m.receiptHandle(), delay);
                } catch (RuntimeException e) {
                    // The message simply reappears after its current visibility timeout.
                    log.warn("could not change visibility: {}", e.getMessage());
                }
                yield "retry";
            }
            case DeadLetter dl -> deadLetter(m, dl.reason());
        };
    }

    private String deadLetter(ReceivedMessage m, String reason) {
        Map<String, String> attributes = new LinkedHashMap<>(m.attributes());
        attributes.put(MessageAttributes.FAILURE_REASON, truncate(reason, 1000));
        attributes.put("originalMessageId", m.messageId());
        attributes.put("receiveCount", Integer.toString(m.receiveCount()));
        QueueClient.SendResult sent = queue.send(settings.deadLetterQueue(),
                List.of(OutboundMessage.of(m.messageId(), m.body(), attributes)));
        if (!sent.allSucceeded()) {
            // Leave the message in place; it reappears and the queue's redrive policy is the final safety net.
            log.error("could not dead-letter message {}: {}", m.messageId(), sent.failed());
            return "dead_letter_failed";
        }
        queue.delete(settings.queue(), List.of(m.receiptHandle()));
        log.warn("dead-lettered message queue={} messageId={} reason=\"{}\"", settings.queue(), m.messageId(), reason);
        return "dead_letter";
    }

    private ScheduledFuture<?> scheduleHeartbeat(ReceivedMessage m) {
        if (heartbeats == null || heartbeats.isShutdown()) {
            return null;
        }
        long periodMs = Math.max(1000, settings.visibilityTimeout().toMillis() / 2);
        return heartbeats.scheduleAtFixedRate(() -> {
            try {
                queue.changeVisibility(settings.queue(), m.receiptHandle(), settings.visibilityTimeout());
            } catch (RuntimeException e) {
                log.debug("visibility heartbeat failed for {}: {}", m.messageId(), e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
