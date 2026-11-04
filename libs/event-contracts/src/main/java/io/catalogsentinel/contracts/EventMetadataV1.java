package io.catalogsentinel.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Transport metadata. Never part of an event's idempotency fingerprint: retries may legitimately carry a
 * different correlation id or receive timestamp.
 *
 * @param correlationId   propagated through HTTP, SQS message attributes and logs
 * @param producer        free-form producer name, for example {@code catalog-batch}
 * @param ingestionJobId  set by the batch pipeline so that per-job progress can be derived
 * @param acceptedAt      when the platform first accepted the event (used for end-to-end latency)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventMetadataV1(
        String correlationId,
        String producer,
        Long ingestionJobId,
        Instant acceptedAt) {
}
