package io.catalogsentinel.core.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral queue port modelled on SQS semantics (at-least-once delivery, visibility timeouts,
 * receive counts). The production adapter wraps the AWS SDK v2 {@code SqsClient}; tests use an in-memory
 * implementation that reproduces visibility expiry and redrive-to-DLQ behaviour.
 */
public interface QueueClient {

    /** Long-polls up to {@code maxMessages}; received messages are invisible to others for {@code visibility}. */
    List<ReceivedMessage> receive(String queue, int maxMessages, Duration wait, Duration visibility);

    /** Acknowledges messages. Deleting with a stale receipt handle is a no-op, not an error. */
    void delete(String queue, List<String> receiptHandles);

    /** Re-schedules a message: it becomes visible again after {@code visibility} (used for backoff). */
    void changeVisibility(String queue, String receiptHandle, Duration visibility);

    /** Sends messages (batched by the adapter). Never throws for per-message failures; reports them instead. */
    SendResult send(String queue, List<OutboundMessage> messages);

    QueueDepth depth(String queue);

    record OutboundMessage(String id, String body, Map<String, String> attributes, Duration delay) {
        public OutboundMessage {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            delay = delay == null ? Duration.ZERO : delay;
        }

        public static OutboundMessage of(String id, String body, Map<String, String> attributes) {
            return new OutboundMessage(id, body, attributes, Duration.ZERO);
        }
    }

    /**
     * @param receiveCount how many times the message has been received, including this time
     * @param sentAt       when the message was first enqueued
     */
    record ReceivedMessage(String messageId, String receiptHandle, String body, Map<String, String> attributes,
                           int receiveCount, Instant sentAt) {
        public ReceivedMessage {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    /** @param failed message id to failure reason */
    record SendResult(List<String> succeeded, Map<String, String> failed) {
        public SendResult {
            succeeded = List.copyOf(succeeded);
            failed = Map.copyOf(failed);
        }

        public boolean allSucceeded() {
            return failed.isEmpty();
        }
    }

    record QueueDepth(long visible, long inFlight, long delayed) {
        public long total() {
            return visible + inFlight + delayed;
        }
    }
}
