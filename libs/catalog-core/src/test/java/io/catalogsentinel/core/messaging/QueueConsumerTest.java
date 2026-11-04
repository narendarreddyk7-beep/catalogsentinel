package io.catalogsentinel.core.messaging;

import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.testsupport.InMemoryQueueClient;
import io.catalogsentinel.core.testsupport.MutableClock;
import io.catalogsentinel.domain.common.DomainValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QueueConsumerTest {

    private static final String Q = "events";
    private static final String DLQ = "events-dlq";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
    private final InMemoryQueueClient queue = new InMemoryQueueClient(clock).createQueue(Q).createQueue(DLQ);
    private final List<String> metricLog = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConsumerMetrics metrics = new ConsumerMetrics() {
        @Override
        public void received(String queue, int count) {
        }

        @Override
        public void completed(String queue, String outcome, Duration processingTime) {
            metricLog.add(outcome);
        }

        @Override
        public void paused(String queue, Duration pause) {
            metricLog.add("paused");
        }
    };

    private QueueConsumer consumer(MessageHandler handler, int maxAttempts) {
        return consumer(handler, maxAttempts, 2, () -> true);
    }

    private QueueConsumer consumer(MessageHandler handler, int maxAttempts, int concurrency,
                                   java.util.function.BooleanSupplier healthy) {
        // Deterministic backoff: always the ceiling (random = 1.0).
        BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), () -> 0.9999999);
        return new QueueConsumer(queue, handler, new QueueConsumer.Settings(Q, DLQ, concurrency, maxAttempts,
                Duration.ofMillis(50), Duration.ofSeconds(30), backoff, 3, Duration.ofMillis(100), Duration.ofSeconds(5)),
                metrics, healthy);
    }

    private void send(String body) {
        queue.send(Q, List.of(OutboundMessage.of(body, body, Map.of(MessageAttributes.CORRELATION_ID, "c-" + body))));
    }

    @Test
    void ackDeletesTheMessage() {
        send("m1");
        assertThat(consumer(m -> MessageHandler.ack("ok"), 3).pollOnce()).isEqualTo(1);
        assertThat(queue.size(Q)).isZero();
        assertThat(metricLog).containsExactly("ack");
    }

    @Test
    void retryBacksOffThenRedeliversWithIncrementedReceiveCount() {
        send("m1");
        AtomicInteger seen = new AtomicInteger();
        List<Integer> receiveCounts = new java.util.ArrayList<>();
        QueueConsumer c = consumer(m -> {
            receiveCounts.add(m.receiveCount());
            return seen.incrementAndGet() < 3 ? MessageHandler.retry("db busy") : MessageHandler.ack("ok");
        }, 5);
        c.pollOnce();
        assertThat(c.pollOnce()).as("hidden during backoff").isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(c.pollOnce()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(3));
        assertThat(c.pollOnce()).as("second backoff is longer (4s ceiling)").isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(c.pollOnce()).isEqualTo(1);
        assertThat(receiveCounts).containsExactly(1, 2, 3);
        assertThat(queue.size(Q)).isZero();
    }

    @Test
    void poisonMessagesGoToTheDlqImmediatelyWithAReason() {
        send("bad");
        consumer(m -> MessageHandler.deadLetter("MALFORMED_JSON: unexpected token"), 5).pollOnce();
        assertThat(queue.size(Q)).isZero();
        assertThat(queue.bodies(DLQ)).containsExactly("bad");
        assertThat(queue.attributes(DLQ).get(0))
                .containsEntry(MessageAttributes.FAILURE_REASON, "MALFORMED_JSON: unexpected token")
                .containsEntry(MessageAttributes.CORRELATION_ID, "c-bad")
                .containsEntry("receiveCount", "1");
    }

    @Test
    void retriesAreBoundedThenDeadLettered() {
        send("m1");
        QueueConsumer c = consumer(m -> MessageHandler.retry("still failing"), 3);
        for (int i = 0; i < 3; i++) {
            c.pollOnce();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(queue.bodies(DLQ)).containsExactly("m1");
        assertThat(queue.attributes(DLQ).get(0).get(MessageAttributes.FAILURE_REASON))
                .startsWith("retries exhausted after 3 attempts");
        assertThat(metricLog).containsExactly("retry", "retry", "dead_letter");
    }

    @Test
    void exceptionsAreClassified() {
        send("invalid");
        send("transient");
        consumer(m -> {
            if (m.body().equals("invalid")) {
                throw new DomainValidationException("title", "is required");
            }
            throw new CannotGetJdbcConnectionException("connection refused", new java.sql.SQLException("refused", "08001"));
        }, 5).pollOnce();
        assertThat(queue.bodies(DLQ)).containsExactly("invalid");
        assertThat(queue.bodies(Q)).containsExactly("transient");
    }

    @Test
    void failedDeadLetteringLeavesTheMessageForRedrive() {
        send("bad");
        queue.failNextSends(1);
        String result = consumer(m -> MessageHandler.deadLetter("poison"), 5).process(
                queue.receive(Q, 1, Duration.ZERO, Duration.ofSeconds(30)).get(0));
        assertThat(result).isEqualTo("dead_letter_failed");
        assertThat(queue.size(Q)).isEqualTo(1);
    }

    @Test
    void redrivePolicyIsTheSafetyNetWhenAConsumerCrashesRepeatedly() {
        InMemoryQueueClient q = new InMemoryQueueClient(clock).withRedrive("work", "work-dlq", 4);
        q.send("work", List.of(OutboundMessage.of("x", "x", Map.of())));
        for (int i = 0; i < 4; i++) {
            assertThat(q.receive("work", 1, Duration.ZERO, Duration.ofSeconds(30))).hasSize(1); // crash: never deleted
            clock.advance(Duration.ofSeconds(31));
        }
        assertThat(q.receive("work", 1, Duration.ZERO, Duration.ofSeconds(30))).isEmpty();
        assertThat(q.bodies("work-dlq")).containsExactly("x");
    }

    @Test
    void threadedConsumerRespectsConcurrencyAndDrainsOnStop() throws Exception {
        for (int i = 0; i < 40; i++) {
            send("m" + i);
        }
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        QueueConsumer c = consumer(m -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            done.incrementAndGet();
            return MessageHandler.ack("ok");
        }, 3, 4, () -> true);
        c.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (done.get() < 40 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(c.stop()).isTrue();
        assertThat(done.get()).isEqualTo(40);
        assertThat(maxConcurrent.get()).isBetween(1, 4);
        assertThat(queue.size(Q)).isZero();
        assertThat(c.inFlight()).isZero();
    }

    @Test
    void slowHandlersKeepTheirMessageInvisibleWithAVisibilityHeartbeat() throws Exception {
        send("slow");
        List<Duration> extensions = new java.util.concurrent.CopyOnWriteArrayList<>();
        QueueClient recording = new QueueClient() {
            @Override
            public List<QueueClient.ReceivedMessage> receive(String q, int max, Duration wait, Duration visibility) {
                return queue.receive(q, max, wait, visibility);
            }

            @Override
            public void delete(String q, List<String> receiptHandles) {
                queue.delete(q, receiptHandles);
            }

            @Override
            public void changeVisibility(String q, String receiptHandle, Duration visibility) {
                extensions.add(visibility);
                queue.changeVisibility(q, receiptHandle, visibility);
            }

            @Override
            public QueueClient.SendResult send(String q, List<OutboundMessage> messages) {
                return queue.send(q, messages);
            }

            @Override
            public QueueClient.QueueDepth depth(String q) {
                return queue.depth(q);
            }
        };
        java.util.concurrent.CountDownLatch handled = new java.util.concurrent.CountDownLatch(1);
        BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), () -> 0.5);
        // Visibility 2 s, so the heartbeat fires every second; the handler needs 3.5 s.
        QueueConsumer c = new QueueConsumer(recording, m -> {
            try {
                Thread.sleep(3_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handled.countDown();
            return MessageHandler.ack("ok");
        }, new QueueConsumer.Settings(Q, DLQ, 1, 3, Duration.ofMillis(50), Duration.ofSeconds(2), backoff, 3,
                Duration.ofMillis(100), Duration.ofSeconds(10)), metrics, () -> true);
        c.start();
        assertThat(handled.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(c.stop()).isTrue();

        // Each extension resets the full visibility timeout, so the message cannot reappear mid-processing.
        assertThat(extensions).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(v -> assertThat(v).isEqualTo(Duration.ofSeconds(2)));
        assertThat(queue.size(Q)).isZero();
    }

    @Test
    void consecutiveDependencyOutagesPausePollingUntilTheProbePasses() throws Exception {
        for (int i = 0; i < 10; i++) {
            send("m" + i);
        }
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean databaseUp = new java.util.concurrent.atomic.AtomicBoolean(false);
        QueueConsumer c = consumer(m -> {
            handled.incrementAndGet();
            if (!databaseUp.get()) {
                throw new CannotGetJdbcConnectionException("connection refused", new java.sql.SQLException("refused", "08001"));
            }
            return MessageHandler.ack("ok");
        }, 50, 1, () -> {
            probes.incrementAndGet();
            return databaseUp.get();
        });
        c.start();
        long deadline = System.currentTimeMillis() + 5_000;
        while (probes.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        int handledDuringOutage = handled.get();
        Thread.sleep(150);
        assertThat(handled.get()).as("no new messages consumed while paused").isEqualTo(handledDuringOutage);
        assertThat(metricLog).contains("paused");
        assertThat(queue.size(DLQ)).as("an outage must not flood the DLQ").isZero();
        c.stop();
    }

    @Test
    void backoffCeilingsDoubleUpToTheCap() {
        BackoffPolicy p = BackoffPolicy.fullJitter(Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertThat(p.ceiling(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.ceiling(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(p.ceiling(10)).isEqualTo(Duration.ofSeconds(10));
        for (int i = 0; i < 1000; i++) {
            assertThat(p.delay(4)).isBetween(Duration.ZERO, Duration.ofSeconds(8));
        }
    }
}
