package io.catalogsentinel.core.outbox;

import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.messaging.BackoffPolicy;
import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.messaging.QueueClient.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes outbox rows to SQS. Each run claims a batch with {@code FOR UPDATE SKIP LOCKED} inside a short
 * transaction, sends it, and records the result in the same transaction, so several relay instances can run
 * concurrently without publishing a row twice in overlapping runs.
 *
 * <p>Delivery is at-least-once: if SQS accepts a batch but the commit fails, the rows stay PENDING and are sent
 * again; consumers are idempotent. Rows that keep failing back off exponentially and become FAILED after
 * {@code maxAttempts} (alerted on; replayable with {@link OutboxRepository#requeueFailed}).
 *
 * <p>Trade-off: the claim transaction stays open during the SQS call. Batches are small and the SQS client has
 * call timeouts, which bounds lock duration; the alternative (lease columns) avoids that at the cost of extra
 * writes and lease-expiry logic.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final QueueClient queue;
    private final String destinationQueue;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final BackoffPolicy backoff;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository outbox, QueueClient queue, String destinationQueue, TransactionTemplate tx,
                       Clock clock, BackoffPolicy backoff, int batchSize, int maxAttempts) {
        this.outbox = outbox;
        this.queue = queue;
        this.destinationQueue = destinationQueue;
        this.tx = tx;
        this.clock = clock;
        this.backoff = backoff;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Publishes one batch; returns the number of rows published. */
    public RelayResult relayOnce() {
        RelayResult result = tx.execute(status -> {
            Instant now = clock.instant();
            List<OutboxMessage> due = outbox.claimDue(now, batchSize);
            if (due.isEmpty()) {
                return new RelayResult(0, 0);
            }
            List<OutboundMessage> messages = new ArrayList<>(due.size());
            for (OutboxMessage m : due) {
                Map<String, String> attributes = new LinkedHashMap<>(m.headers());
                attributes.put(MessageAttributes.EVENT_TYPE, m.eventType());
                attributes.put(MessageAttributes.EVENT_ID, m.eventId().toString());
                messages.add(OutboundMessage.of(Long.toString(m.outboxId()), m.payloadJson(), attributes));
            }
            SendResult sent = queue.send(destinationQueue, messages);
            List<Long> published = sent.succeeded().stream().map(Long::parseLong).toList();
            outbox.markPublished(published, now);
            for (OutboxMessage m : due) {
                String error = sent.failed().get(Long.toString(m.outboxId()));
                if (error != null) {
                    Instant next = now.plus(backoff.delay(m.attempts() + 1));
                    outbox.recordFailedAttempt(m.outboxId(), error, next, maxAttempts);
                }
            }
            return new RelayResult(published.size(), sent.failed().size());
        });
        if (result != null && result.failed() > 0) {
            log.warn("outbox relay: published={} failed={}", result.published(), result.failed());
        }
        return result == null ? new RelayResult(0, 0) : result;
    }

    /** Relays until the backlog is empty or a batch makes no progress. */
    public int drain(int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            RelayResult r = relayOnce();
            total += r.published();
            if (r.published() == 0) {
                break;
            }
        }
        return total;
    }

    public record RelayResult(int published, int failed) {
    }
}
