package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.core.common.ConflictException;

import java.util.UUID;

/** An event id that was already processed arrived again with different content (HTTP 409). */
public class EventIdReusedException extends ConflictException {

    private final UUID eventId;

    public EventIdReusedException(UUID eventId) {
        super("EVENT_ID_REUSED", "event id " + eventId + " was already processed with different data");
        this.eventId = eventId;
    }

    public UUID eventId() {
        return eventId;
    }
}
