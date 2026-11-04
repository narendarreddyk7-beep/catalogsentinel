package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.contracts.ListingChangedV1;
import io.catalogsentinel.core.common.UnprocessableException;
import io.catalogsentinel.core.json.JsonCodec;
import io.catalogsentinel.core.listing.ListingRepository;
import io.catalogsentinel.core.listing.ListingRepository.ListingWrite;
import io.catalogsentinel.core.listing.ListingRepository.LockedListing;
import io.catalogsentinel.core.outbox.OutboxMessage;
import io.catalogsentinel.core.outbox.OutboxRepository;
import io.catalogsentinel.core.source.CatalogSource;
import io.catalogsentinel.core.source.SourceRepository;
import io.catalogsentinel.domain.common.DomainValidationException;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.catalogsentinel.domain.listing.ListingContent;
import io.catalogsentinel.domain.listing.ListingDecision;
import io.catalogsentinel.domain.listing.ListingEventDecider;
import io.catalogsentinel.domain.listing.ListingState;
import io.catalogsentinel.domain.normalize.NormalizedListing;
import io.catalogsentinel.domain.normalize.ProductNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies listing events exactly once in effect, under at-least-once delivery.
 *
 * <p>Per event, in one READ COMMITTED transaction:
 * <ol>
 *   <li>Look up the event id in {@code processed_events}: an exact replay returns the stored outcome; a
 *       reused id with a different fingerprint is rejected.</li>
 *   <li>Lock the listing row ({@code FOR UPDATE}) or create it ({@code ON CONFLICT DO NOTHING}), so concurrent
 *       events for one listing are serialized and creation races are resolved by the unique constraint.</li>
 *   <li>Decide with the pure {@link ListingEventDecider} and write the listing.</li>
 *   <li>Record the event in {@code processed_events}. If a concurrent delivery of the same event id committed
 *       first, the insert affects no row; the transaction is rolled back and retried, and the retry returns the
 *       stored outcome. The primary key is therefore the final arbiter of idempotency.</li>
 *   <li>Append a {@code ListingChangedV1} to the outbox when the catalog changed, atomically with the state.</li>
 * </ol>
 */
@Service
public class ListingIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ListingIngestionService.class);
    static final int MAX_ATTEMPTS = 4;

    private final SourceRepository sources;
    private final ListingRepository listings;
    private final ProcessedEventRepository processedEvents;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final JsonCodec json;
    private final Clock clock;
    private final IngestionMetrics metrics;

    public ListingIngestionService(SourceRepository sources, ListingRepository listings,
                                   ProcessedEventRepository processedEvents, OutboxRepository outbox,
                                   TransactionTemplate tx, JsonCodec json, Clock clock, IngestionMetrics metrics) {
        this.sources = sources;
        this.listings = listings;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    public IngestionResult ingest(IngestionRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                IngestionResult result = tx.execute(status -> ingestInTransaction(request));
                Duration latency = Duration.between(request.receivedAt(), clock.instant());
                metrics.recordOutcome(request.path(), result.outcome(), result.duplicate(),
                        latency.isNegative() ? Duration.ZERO : latency);
                return result;
            } catch (ConcurrentDuplicateException e) {
                metrics.recordRetry("concurrent_duplicate");
                log.debug("concurrent delivery of event {} detected; retrying to return stored outcome", e.eventId);
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("event " + e.eventId + " kept racing with itself", e);
                }
            } catch (PessimisticLockingFailureException e) {
                metrics.recordRetry("lock_failure");
                if (attempt >= MAX_ATTEMPTS) {
                    metrics.recordFailure(request.path(), "lock_failure");
                    throw e;
                }
                sleepQuietly(10L * attempt);
            } catch (RuntimeException e) {
                metrics.recordFailure(request.path(), e.getClass().getSimpleName());
                throw e;
            }
        }
    }

    private IngestionResult ingestInTransaction(IngestionRequest request) {
        IncomingListingEvent event = request.event();

        Optional<ProcessedEventRecord> prior = processedEvents.find(event.eventId());
        if (prior.isPresent()) {
            return replay(prior.get(), event);
        }

        CatalogSource source = sources.findByCode(event.sourceCode()).orElseThrow(() ->
                new UnprocessableException("UNKNOWN_SOURCE", "source '" + event.sourceCode() + "' is not registered"));
        if (!source.enabled()) {
            throw new UnprocessableException("SOURCE_DISABLED", "source '" + source.code() + "' is disabled");
        }
        if (request.ingestionJobId() != null && !processedEvents.ingestionJobExists(request.ingestionJobId())) {
            throw new DomainValidationException("metadata.ingestionJobId",
                    "refers to unknown ingestion job " + request.ingestionJobId());
        }

        Instant now = clock.instant();
        long listingId;
        ListingState current;
        ListingDecision decision;
        Optional<LockedListing> locked = listings.lockForUpdate(source.sourceId(), event.externalProductId());
        if (locked.isPresent()) {
            listingId = locked.get().listingId();
            current = locked.get().state();
            decision = ListingEventDecider.decide(current, event);
            if (decision.requiresWrite(current)) {
                listings.update(listingId, toWrite(source, event, decision, now));
            }
        } else {
            current = null;
            decision = ListingEventDecider.decide(null, event);
            Optional<Long> created = listings.insertIfAbsent(toWrite(source, event, decision, now));
            if (created.isPresent()) {
                listingId = created.get();
            } else {
                // Another transaction created this listing after our lock attempt: decide against its row.
                LockedListing winner = listings.lockForUpdate(source.sourceId(), event.externalProductId())
                        .orElseThrow(() -> new IllegalStateException("listing vanished after insert conflict"));
                listingId = winner.listingId();
                current = winner.state();
                decision = ListingEventDecider.decide(current, event);
                if (decision.requiresWrite(current)) {
                    listings.update(listingId, toWrite(source, event, decision, now));
                }
            }
        }

        IngestionOutcome outcome = decision.outcome();
        Long appliedVersion = (outcome == IngestionOutcome.STALE_IGNORED || outcome == IngestionOutcome.VERSION_CONFLICT)
                ? null : decision.resultingVersion();
        ProcessedEventRecord record = new ProcessedEventRecord(event.eventId(), source.sourceId(),
                event.externalProductId(), listingId, event.type(), event.fingerprint(), event.sourceVersion(),
                appliedVersion, outcome, decision.reason(), request.path(), request.ingestionJobId(),
                request.correlationId(), event.occurredAt(), request.receivedAt(), now);
        if (!processedEvents.insert(record)) {
            throw new ConcurrentDuplicateException(event.eventId());
        }

        boolean tombstoneForUnseenListing = current == null && outcome == IngestionOutcome.WITHDRAWN;
        if (outcome.changesCatalog() && !tombstoneForUnseenListing) {
            String contentHash = decision.replaceContent() ? event.contentHash() : current == null ? null : current.contentHash();
            appendListingChanged(source, event, listingId, decision, contentHash, request.correlationId(), now);
        }
        if (outcome == IngestionOutcome.VERSION_CONFLICT) {
            log.info("listing event rejected outcome=VERSION_CONFLICT eventId={} source={} externalId={} reason=\"{}\"",
                    event.eventId(), source.code(), event.externalProductId(), decision.reason());
        } else {
            log.debug("listing event processed outcome={} eventId={} listingId={} version={}",
                    outcome, event.eventId(), listingId, appliedVersion);
        }
        return record.toResult(false);
    }

    private IngestionResult replay(ProcessedEventRecord prior, IncomingListingEvent event) {
        if (!prior.requestFingerprint().equals(event.fingerprint())) {
            log.info("listing event rejected code=EVENT_ID_REUSED eventId={}", event.eventId());
            throw new EventIdReusedException(event.eventId());
        }
        return prior.toResult(true);
    }

    private ListingWrite toWrite(CatalogSource source, IncomingListingEvent event, ListingDecision decision, Instant now) {
        ListingContent c = decision.replaceContent() ? event.content() : null;
        NormalizedListing n = c == null ? null : ProductNormalizer.normalize(c);
        return new ListingWrite(
                source.sourceId(),
                event.externalProductId(),
                decision.resultingStatus(),
                decision.resultingVersion(),
                event.eventId(),
                event.occurredAt(),
                now,
                c == null ? null : c.title(),
                c == null ? null : c.brand(),
                c == null ? null : c.model(),
                c == null ? null : c.category(),
                c == null ? null : c.gtin(),
                c == null ? null : json.write(c.attributes()),
                n == null ? null : n.title(),
                n == null ? null : n.brand(),
                n == null ? null : n.model(),
                n == null ? null : n.category(),
                n == null ? null : n.categoryLeaf(),
                n == null ? null : json.write(n.attributes().asMap()),
                n == null ? null : ProductNormalizer.VERSION,
                c == null ? null : c.contentHash());
    }

    private void appendListingChanged(CatalogSource source, IncomingListingEvent event, long listingId,
                                      ListingDecision decision, String contentHash, String correlationId, Instant now) {
        // Deterministic id: each listing event causes at most one change event.
        UUID changeId = UUID.nameUUIDFromBytes(("listing-changed:" + event.eventId()).getBytes(StandardCharsets.UTF_8));
        ListingChangedV1 changed = new ListingChangedV1(changeId, ListingChangedV1.SCHEMA_VERSION, listingId,
                source.code(), event.externalProductId(), decision.resultingVersion(), decision.resultingStatus().name(),
                decision.outcome().name(), contentHash, now, event.eventId(), correlationId);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("causationEventId", event.eventId().toString());
        if (correlationId != null) {
            headers.put("correlationId", correlationId);
        }
        outbox.append(OutboxMessage.newMessage(changeId, "listing", Long.toString(listingId),
                ListingChangedV1.EVENT_TYPE, json.write(changed), headers), now);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Internal signal: a concurrent transaction recorded the same event id first. Triggers rollback + retry. */
    static final class ConcurrentDuplicateException extends RuntimeException {
        final UUID eventId;

        ConcurrentDuplicateException(UUID eventId) {
            super("concurrent duplicate of event " + eventId, null, false, false);
            this.eventId = eventId;
        }
    }
}
