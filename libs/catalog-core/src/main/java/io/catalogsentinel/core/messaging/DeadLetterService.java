package io.catalogsentinel.core.messaging;

import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operator tooling for dead-letter queues: inspect messages and replay them to their source queue (for example
 * after registering a missing source or deploying a fix). Replays carry an incremented {@code replayCount}
 * attribute; because processing is idempotent, replaying a message that was already applied is harmless.
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private final QueueClient queue;
    private final QueueNames names;

    public DeadLetterService(QueueClient queue, QueueNames names) {
        this.queue = queue;
        this.names = names;
    }

    /**
     * Returns up to {@code max} messages without removing them: they are received with a short visibility
     * timeout and therefore reappear in the DLQ moments later.
     */
    public List<ReceivedMessage> peek(String dlq, int max) {
        names.sourceOfDlq(dlq);
        return queue.receive(dlq, Math.min(10, Math.max(1, max)), Duration.ZERO, Duration.ofSeconds(1));
    }

    public ReplayResult replay(String dlq, int maxMessages) {
        String source = names.sourceOfDlq(dlq);
        int replayed = 0;
        int failed = 0;
        List<String> replayedIds = new ArrayList<>();
        while (replayed + failed < maxMessages) {
            List<ReceivedMessage> batch = queue.receive(dlq, Math.min(10, maxMessages - replayed - failed),
                    Duration.ZERO, Duration.ofSeconds(30));
            if (batch.isEmpty()) {
                break;
            }
            for (ReceivedMessage m : batch) {
                Map<String, String> attributes = new LinkedHashMap<>(m.attributes());
                int count = Integer.parseInt(attributes.getOrDefault(MessageAttributes.REPLAY_COUNT, "0"));
                attributes.put(MessageAttributes.REPLAY_COUNT, Integer.toString(count + 1));
                attributes.remove(MessageAttributes.FAILURE_REASON);
                QueueClient.SendResult sent = queue.send(source, List.of(OutboundMessage.of(m.messageId(), m.body(), attributes)));
                if (sent.allSucceeded()) {
                    queue.delete(dlq, List.of(m.receiptHandle()));
                    replayed++;
                    replayedIds.add(m.messageId());
                } else {
                    failed++;
                }
            }
        }
        log.info("replayed {} message(s) from {} to {} ({} failed)", replayed, dlq, source, failed);
        return new ReplayResult(dlq, source, replayed, failed, replayedIds);
    }

    public record ReplayResult(String deadLetterQueue, String sourceQueue, int replayed, int failed,
                               List<String> messageIds) {
    }
}
