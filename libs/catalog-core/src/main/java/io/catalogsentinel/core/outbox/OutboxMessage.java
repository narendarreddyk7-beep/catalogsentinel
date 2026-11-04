package io.catalogsentinel.core.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An event to be published reliably. When written, {@code outboxId} and {@code attempts} are ignored; when
 * read by the relay they describe the stored row.
 */
public record OutboxMessage(
        long outboxId,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Map<String, String> headers,
        int attempts,
        Instant createdAt) {

    public static OutboxMessage newMessage(UUID eventId, String aggregateType, String aggregateId, String eventType,
                                           String payloadJson, Map<String, String> headers) {
        return new OutboxMessage(0, eventId, aggregateType, aggregateId, eventType, payloadJson,
                headers == null ? Map.of() : headers, 0, null);
    }
}
