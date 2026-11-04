package io.catalogsentinel.core.testsupport;

import io.catalogsentinel.core.messaging.QueueClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory queue with the SQS behaviours the consumer relies on:
 * <ul>
 *   <li>received messages are invisible until their visibility timeout expires, then redelivered;</li>
 *   <li>each receive increments the receive count and issues a new receipt handle, so an old handle can no
 *       longer delete or extend the message;</li>
 *   <li>a redrive policy moves a message to its DLQ when a receive would exceed {@code maxReceiveCount};</li>
 *   <li>send failures can be injected.</li>
 * </ul>
 * Time comes from the supplied clock so tests can expire visibility deterministically.
 */
public final class InMemoryQueueClient implements QueueClient {

    private final Clock clock;
    private final Map<String, List<Stored>> queues = new HashMap<>();
    private final Map<String, Redrive> redrive = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile int failNextSends;

    public InMemoryQueueClient(Clock clock) {
        this.clock = clock;
    }

    public synchronized InMemoryQueueClient createQueue(String name) {
        queues.putIfAbsent(name, new ArrayList<>());
        return this;
    }

    public synchronized InMemoryQueueClient withRedrive(String queue, String dlq, int maxReceiveCount) {
        createQueue(queue).createQueue(dlq);
        redrive.put(queue, new Redrive(dlq, maxReceiveCount));
        return this;
    }

    /** The next {@code n} send calls fail every message (simulates an SQS outage). */
    public void failNextSends(int n) {
        this.failNextSends = n;
    }

    @Override
    public synchronized List<ReceivedMessage> receive(String queue, int maxMessages, Duration wait, Duration visibility) {
        List<Stored> q = require(queue);
        Instant now = clock.instant();
        List<ReceivedMessage> out = new ArrayList<>();
        for (Stored m : new ArrayList<>(q)) {
            if (out.size() >= maxMessages) {
                break;
            }
            if (m.visibleAt.isAfter(now)) {
                continue;
            }
            Redrive policy = redrive.get(queue);
            if (policy != null && m.receiveCount + 1 > policy.maxReceiveCount) {
                q.remove(m);
                m.receiveCount = 0;
                m.visibleAt = now;
                m.receipt = null;
                require(policy.dlq).add(m);
                continue;
            }
            m.receiveCount++;
            m.receipt = "rh-" + m.id + "-" + m.receiveCount + "-" + UUID.randomUUID();
            m.visibleAt = now.plus(visibility);
            out.add(new ReceivedMessage(m.id, m.receipt, m.body, m.attributes, m.receiveCount, m.sentAt));
        }
        return out;
    }

    @Override
    public synchronized void delete(String queue, List<String> receiptHandles) {
        require(queue).removeIf(m -> m.receipt != null && receiptHandles.contains(m.receipt));
    }

    @Override
    public synchronized void changeVisibility(String queue, String receiptHandle, Duration visibility) {
        for (Stored m : require(queue)) {
            if (receiptHandle.equals(m.receipt)) {
                m.visibleAt = clock.instant().plus(visibility);
            }
        }
    }

    @Override
    public synchronized SendResult send(String queue, List<OutboundMessage> messages) {
        List<String> ok = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        boolean fail = failNextSends > 0;
        if (fail) {
            failNextSends--;
        }
        for (OutboundMessage m : messages) {
            if (fail || !queues.containsKey(queue)) {
                failed.put(m.id(), fail ? "injected failure" : "queue does not exist");
                continue;
            }
            Stored s = new Stored();
            s.id = "msg-" + sequence.incrementAndGet();
            s.body = m.body();
            s.attributes = m.attributes();
            s.sentAt = clock.instant();
            s.visibleAt = clock.instant().plus(m.delay());
            queues.get(queue).add(s);
            ok.add(m.id());
        }
        return new SendResult(ok, failed);
    }

    @Override
    public synchronized QueueDepth depth(String queue) {
        Instant now = clock.instant();
        long visible = 0;
        long inFlight = 0;
        for (Stored m : require(queue)) {
            if (m.visibleAt.isAfter(now)) {
                if (m.receipt != null) {
                    inFlight++;
                }
            } else {
                visible++;
            }
        }
        return new QueueDepth(visible, inFlight, 0);
    }

    /** All bodies currently stored in a queue, in order (visible or not). */
    public synchronized List<String> bodies(String queue) {
        return require(queue).stream().map(m -> m.body).toList();
    }

    public synchronized List<Map<String, String>> attributes(String queue) {
        return require(queue).stream().map(m -> m.attributes).toList();
    }

    public synchronized int size(String queue) {
        return require(queue).size();
    }

    private List<Stored> require(String queue) {
        List<Stored> q = queues.get(queue);
        if (q == null) {
            throw new IllegalArgumentException("unknown queue " + queue);
        }
        return q;
    }

    private static final class Stored {
        String id;
        String body;
        Map<String, String> attributes;
        Instant sentAt;
        Instant visibleAt;
        int receiveCount;
        String receipt;
    }

    private record Redrive(String dlq, int maxReceiveCount) {
    }
}
