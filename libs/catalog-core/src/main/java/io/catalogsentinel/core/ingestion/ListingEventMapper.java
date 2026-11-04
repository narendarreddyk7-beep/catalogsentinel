package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.contracts.ListingPayloadV1;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.common.DomainValidationException.Violation;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.listing.ListingEventType;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a {@link ListingEventV1} contract and converts it to a domain {@link IncomingListingEvent}.
 *
 * <p>The HTTP API (sync and async paths) and the SQS worker all use this single code path, so an event the
 * API accepts can never be rejected as invalid by the worker, and vice versa. Every violation is collected
 * and reported in one response.
 */
public final class ListingEventMapper {

    /** Tolerated producer clock skew for {@code occurredAt}. */
    public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    public ListingEventMapper(Clock clock) {
        this.clock = clock;
    }

    public IncomingListingEvent toDomain(ListingEventV1 e) {
        if (e == null) {
            throw new DomainValidationException("body", "is required");
        }
        List<Violation> violations = new ArrayList<>();
        if (e.schemaVersion() == null || e.schemaVersion() != ListingEventV1.SCHEMA_VERSION) {
            violations.add(new Violation("schemaVersion", "must be " + ListingEventV1.SCHEMA_VERSION));
        }
        ListingEventType type = parseType(e.eventType());
        if (type == null) {
            violations.add(new Violation("eventType", "must be LISTING_UPSERTED or LISTING_WITHDRAWN"));
        }
        if (e.sourceVersion() == null) {
            violations.add(new Violation("sourceVersion", "is required"));
        }
        if (e.occurredAt() != null && e.occurredAt().isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            violations.add(new Violation("occurredAt",
                    "is more than " + MAX_FUTURE_SKEW.toMinutes() + " minutes in the future"));
        }

        ListingPayloadV1 p = e.payload();
        // With an unknown type, infer the intent from payload presence so that no spurious payload error is added.
        ListingEventType effectiveType = type != null ? type
                : (p != null ? ListingEventType.LISTING_UPSERTED : ListingEventType.LISTING_WITHDRAWN);
        ListingContent content = null;
        boolean payloadProblemReported = false;
        if (effectiveType == ListingEventType.LISTING_WITHDRAWN) {
            if (p != null) {
                violations.add(new Violation("payload", "must be absent for LISTING_WITHDRAWN"));
                payloadProblemReported = true;
            }
        } else if (p != null) {
            try {
                content = ListingContent.of(p.title(), p.brand(), p.model(), p.category(), p.gtin(), p.attributes());
            } catch (DomainValidationException ex) {
                violations.addAll(ex.violations());
                payloadProblemReported = true;
            }
        }

        try {
            IncomingListingEvent event = new IncomingListingEvent(e.eventId(), effectiveType, e.sourceCode(),
                    e.externalProductId(), e.sourceVersion() == null ? 0 : e.sourceVersion(), e.occurredAt(),
                    effectiveType == ListingEventType.LISTING_WITHDRAWN ? null : content);
            if (violations.isEmpty()) {
                return event;
            }
        } catch (DomainValidationException ex) {
            for (Violation v : ex.violations()) {
                boolean alreadyExplained = payloadProblemReported && v.field().equals("payload");
                if (!alreadyExplained && !violations.contains(v)) {
                    violations.add(v);
                }
            }
        }
        throw new DomainValidationException(violations);
    }

    private static ListingEventType parseType(String raw) {
        if (ListingEventV1.UPSERTED.equals(raw)) {
            return ListingEventType.LISTING_UPSERTED;
        }
        if (ListingEventV1.WITHDRAWN.equals(raw)) {
            return ListingEventType.LISTING_WITHDRAWN;
        }
        return null;
    }
}
