package io.catalogsentinel.core.messaging;

/**
 * Logical queue names, configured per environment.
 *
 * @param listingEvents     inbound listing events (async API and batch)
 * @param listingEventsDlq  dead letters of {@code listingEvents}
 * @param changeEvents      outbox-published {@code ListingChangedV1} events
 * @param changeEventsDlq   dead letters of {@code changeEvents}
 */
public record QueueNames(String listingEvents, String listingEventsDlq, String changeEvents, String changeEventsDlq) {

    public static QueueNames defaults() {
        return new QueueNames("catalog-listing-events", "catalog-listing-events-dlq",
                "catalog-change-events", "catalog-change-events-dlq");
    }

    public String dlqFor(String queue) {
        if (queue.equals(listingEvents)) {
            return listingEventsDlq;
        }
        if (queue.equals(changeEvents)) {
            return changeEventsDlq;
        }
        throw new IllegalArgumentException("no dead-letter queue configured for " + queue);
    }

    public String sourceOfDlq(String dlq) {
        if (dlq.equals(listingEventsDlq)) {
            return listingEvents;
        }
        if (dlq.equals(changeEventsDlq)) {
            return changeEvents;
        }
        throw new IllegalArgumentException(dlq + " is not a known dead-letter queue");
    }
}
