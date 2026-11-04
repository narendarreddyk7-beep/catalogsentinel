package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.EventMetadataV1;
import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.MessageAttributes;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.messaging.QueueClient;
import io.catalogsentinel.core.messaging.QueueClient.OutboundMessage;
import io.catalogsentinel.core.messaging.QueueClient.SendResult;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.common.DomainValidationException.Violation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accepts listing events for asynchronous processing: validates every event with the same
 * {@link ListingEventMapper} the worker uses, then enqueues them. Nothing is enqueued unless the whole batch
 * is valid. A {@code 202 Accepted} therefore means "valid and durably queued"; resubmitting after a partial
 * enqueue failure is safe because processing is idempotent on event id.
 */
public class AsyncIngestionService {

    public static final int MAX_BATCH = 500;

    private final ListingEventMapper mapper;
    private final QueueClient queue;
    private final String queueName;
    private final JsonCodec json;
    private final Clock clock;

    public AsyncIngestionService(ListingEventMapper mapper, QueueClient queue, String queueName, JsonCodec json,
                                 Clock clock) {
        this.mapper = mapper;
        this.queue = queue;
        this.queueName = queueName;
        this.json = json;
        this.clock = clock;
    }

    public AcceptedBatch accept(List<ListingEventV1> events, String correlationId, String producer) {
        if (events == null || events.isEmpty()) {
            throw new DomainValidationException("events", "must contain at least one event");
        }
        if (events.size() > MAX_BATCH) {
            throw new DomainValidationException("events", "must contain at most " + MAX_BATCH + " events");
        }
        List<Violation> violations = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < events.size(); i++) {
            ListingEventV1 e = events.get(i);
            try {
                mapper.toDomain(e);
                if (!seenIds.add(String.valueOf(e.eventId()))) {
                    violations.add(new Violation("events[" + i + "].eventId", "is duplicated within the batch"));
                }
            } catch (DomainValidationException ex) {
                for (Violation v : ex.violations()) {
                    violations.add(new Violation("events[" + i + "]." + v.field(), v.message()));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new DomainValidationException(violations);
        }

        Instant acceptedAt = clock.instant();
        List<OutboundMessage> messages = new ArrayList<>(events.size());
        for (ListingEventV1 e : events) {
            Long jobId = e.metadata() == null ? null : e.metadata().ingestionJobId();
            ListingEventV1 stamped = e.withMetadata(new EventMetadataV1(correlationId, producer, jobId, acceptedAt));
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put(MessageAttributes.EVENT_TYPE, e.eventType());
            attributes.put(MessageAttributes.SCHEMA_VERSION, String.valueOf(ListingEventV1.SCHEMA_VERSION));
            attributes.put(MessageAttributes.EVENT_ID, e.eventId().toString());
            if (correlationId != null) {
                attributes.put(MessageAttributes.CORRELATION_ID, correlationId);
            }
            messages.add(OutboundMessage.of(e.eventId().toString(), json.write(stamped), attributes));
        }
        SendResult result = queue.send(queueName, messages);
        return new AcceptedBatch(result.succeeded(), result.failed(), acceptedAt);
    }

    /** @param failed event id to failure reason; non-empty means the caller should retry those events */
    public record AcceptedBatch(List<String> acceptedEventIds, Map<String, String> failed, Instant acceptedAt) {
        public boolean complete() {
            return failed.isEmpty();
        }
    }
}
