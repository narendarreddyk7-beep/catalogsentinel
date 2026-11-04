package io.catalogsentinel.api.web;

import io.catalogsentinel.contracts.ListingEventV1;
import io.catalogsentinel.core.ingestion.AsyncIngestionService;
import io.catalogsentinel.core.ingestion.AsyncIngestionService.AcceptedBatch;
import io.catalogsentinel.core.ingestion.IngestionPath;
import io.catalogsentinel.core.ingestion.IngestionRequest;
import io.catalogsentinel.core.ingestion.IngestionResult;
import io.catalogsentinel.core.ingestion.ListingEventMapper;
import io.catalogsentinel.core.ingestion.ListingIngestionService;
import io.catalogsentinel.domain.listing.IncomingListingEvent;
import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.catalogsentinel.platform.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listing ingestion endpoints.
 *
 * <ul>
 *   <li>{@code POST /api/v1/listing-events}: apply one event synchronously and return its outcome.</li>
 *   <li>{@code POST /api/v1/ingestion/events}: validate a batch and enqueue it for the worker (202).</li>
 * </ul>
 * Both are idempotent on {@code eventId}: retrying a request is always safe.
 */
@RestController
@Tag(name = "Ingestion", description = "Versioned, idempotent listing events")
public class ListingEventController {

    private final ListingEventMapper mapper;
    private final ListingIngestionService ingestion;
    private final AsyncIngestionService asyncIngestion;
    private final Clock clock;

    public ListingEventController(ListingEventMapper mapper, ListingIngestionService ingestion,
                                  AsyncIngestionService asyncIngestion, Clock clock) {
        this.mapper = mapper;
        this.ingestion = ingestion;
        this.asyncIngestion = asyncIngestion;
        this.clock = clock;
    }

    @PostMapping("/api/v1/listing-events")
    @Operation(summary = "Apply a listing event synchronously",
            description = "201 CREATED for a new listing; 200 for updates, no-ops, stale events and exact replays "
                    + "(header Idempotent-Replay: true); 409 VERSION_CONFLICT or EVENT_ID_REUSED; 422 UNKNOWN_SOURCE "
                    + "or SOURCE_DISABLED; 400 with every field violation.")
    public ResponseEntity<?> ingest(@RequestBody ListingEventV1 body, HttpServletRequest request) {
        Instant receivedAt = clock.instant();
        IncomingListingEvent event = mapper.toDomain(body);
        Long jobId = body.metadata() == null ? null : body.metadata().ingestionJobId();
        IngestionResult r = ingestion.ingest(new IngestionRequest(event, IngestionPath.SYNC_API,
                CorrelationIdFilter.current(), jobId, receivedAt));

        if (r.outcome() == IngestionOutcome.VERSION_CONFLICT) {
            ProblemDetail pd = ApiExceptionHandler.problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "Version conflict",
                    r.reason(), request);
            pd.setProperty("eventId", r.eventId());
            pd.setProperty("listingId", r.listingId());
            pd.setProperty("duplicate", r.duplicate());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
        }
        boolean created = r.outcome() == IngestionOutcome.CREATED && !r.duplicate();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK);
        if (created) {
            builder.location(URI.create("/api/v1/listings/" + r.listingId()));
        }
        if (r.duplicate()) {
            builder.header("Idempotent-Replay", "true");
        }
        return builder.body(IngestionResponse.from(r));
    }

    @PostMapping("/api/v1/ingestion/events")
    @Operation(summary = "Enqueue a batch of listing events for asynchronous processing",
            description = "Validates all events (1-500) with the same rules as the synchronous endpoint. 202 when every "
                    + "event is durably queued; 503 with the failed event ids if the queue rejected some (resubmitting "
                    + "the whole batch is safe); 400 if any event is invalid (nothing is queued).")
    public ResponseEntity<AsyncAcceptedResponse> enqueue(@RequestBody AsyncIngestionRequest body) {
        AcceptedBatch batch = asyncIngestion.accept(body == null ? null : body.events(), CorrelationIdFilter.current(),
                "catalog-api");
        AsyncAcceptedResponse response = new AsyncAcceptedResponse(batch.acceptedEventIds().size(),
                batch.acceptedEventIds(), batch.failed(), batch.acceptedAt());
        if (!batch.complete()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    public record AsyncIngestionRequest(List<ListingEventV1> events) {
    }

    public record AsyncAcceptedResponse(int accepted, List<String> eventIds, Map<String, String> failed,
                                        Instant acceptedAt) {
    }

    public record IngestionResponse(UUID eventId, IngestionOutcome outcome, long listingId, Long appliedVersion,
                                    boolean duplicate, String reason, Instant processedAt) {
        static IngestionResponse from(IngestionResult r) {
            return new IngestionResponse(r.eventId(), r.outcome(), r.listingId(), r.appliedVersion(), r.duplicate(),
                    r.reason(), r.processedAt());
        }
    }
}
