package io.catalogsentinel.core.matching;

import io.catalogsentinel.contracts.ListingChangedV1;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.messaging.MessageHandler;
import io.catalogsentinel.core.messaging.QueueClient.ReceivedMessage;

/**
 * Consumes {@link ListingChangedV1} events published by the outbox and re-matches the listing. Idempotent by
 * construction: matching reads the listing's current state, so duplicate or out-of-order change events converge
 * to the same candidates and links, and no separate consumed-events table is needed.
 */
public class ListingChangedMessageHandler implements MessageHandler {

    private final JsonCodec json;
    private final MatchingService matching;

    public ListingChangedMessageHandler(JsonCodec json, MatchingService matching) {
        this.json = json;
        this.matching = matching;
    }

    @Override
    public Outcome handle(ReceivedMessage message) {
        ListingChangedV1 event;
        try {
            event = json.read(message.body(), ListingChangedV1.class);
        } catch (JsonCodec.JsonCodecException e) {
            return MessageHandler.deadLetter("MALFORMED_JSON: " + e.getMessage());
        }
        if (event.listingId() == null) {
            return MessageHandler.deadLetter("INVALID_EVENT: listingId is required");
        }
        MatchingService.MatchingOutcome outcome = matching.matchListing(event.listingId());
        return MessageHandler.ack(outcome.skippedReason() != null ? "skipped: " + outcome.skippedReason()
                : "matched: " + outcome.decisions() + " link=" + outcome.linkMethod());
    }
}
