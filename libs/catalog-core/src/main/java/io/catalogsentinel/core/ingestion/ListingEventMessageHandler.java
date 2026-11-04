package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.messaging.MessageHandler;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.listing.IncomingListingEvent;

import java.time.Instant;

/**
 * Applies {@link ListingEventV1} messages from SQS through the same validation and ingestion engine as the
 * synchronous API. Classification:
 * <ul>
 *   <li>any recorded outcome (including STALE_IGNORED and VERSION_CONFLICT) and exact duplicates: ack;</li>
 *   <li>malformed JSON, invalid event, reused event id, unknown or disabled source: dead-letter (needs a human; can
 *       be replayed after, for example, registering the source);</li>
 *   <li>database and transaction failures: thrown, then retried with backoff by the consumer.</li>
 * </ul>
 */
public class ListingEventMessageHandler implements MessageHandler {

    private final JsonCodec json;
    private final ListingEventMapper mapper;
    private final ListingIngestionService ingestion;

    public ListingEventMessageHandler(JsonCodec json, ListingEventMapper mapper, ListingIngestionService ingestion) {
        this.json = json;
        this.mapper = mapper;
        this.ingestion = ingestion;
    }

    @Override
    public Outcome handle(ReceivedMessage message) {
        ListingEventV1 contract;
        try {
            contract = json.read(message.body(), ListingEventV1.class);
        } catch (JsonCodec.JsonCodecException e) {
            return MessageHandler.deadLetter("MALFORMED_JSON: " + e.getMessage());
        }
        IncomingListingEvent event;
        try {
            event = mapper.toDomain(contract);
        } catch (DomainValidationException e) {
            return MessageHandler.deadLetter("INVALID_EVENT: " + e.getMessage());
        }
        boolean batch = contract.metadata() != null && contract.metadata().ingestionJobId() != null;
        Instant acceptedAt = contract.metadata() != null && contract.metadata().acceptedAt() != null
                ? contract.metadata().acceptedAt() : message.sentAt();
        String correlationId = contract.metadata() == null ? null : contract.metadata().correlationId();
        try {
            IngestionResult result = ingestion.ingest(new IngestionRequest(event,
                    batch ? IngestionPath.BATCH : IngestionPath.ASYNC_QUEUE, correlationId,
                    batch ? contract.metadata().ingestionJobId() : null, acceptedAt));
            return MessageHandler.ack(result.outcome() + (result.duplicate() ? " (duplicate)" : ""));
        } catch (EventIdReusedException e) {
            return MessageHandler.deadLetter("EVENT_ID_REUSED: " + e.getMessage());
        } catch (UnprocessableException e) {
            return MessageHandler.deadLetter(e.code() + ": " + e.getMessage());
        } catch (DomainValidationException e) {
            return MessageHandler.deadLetter("INVALID_EVENT: " + e.getMessage());
        }
    }
}
