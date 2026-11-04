package io.catalogsentinel.core.ingestion;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.domain.listing.IngestionOutcome;
import io.catalogsentinel.domain.listing.ListingEventType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProcessedEventRepository {

    private static final String COLUMNS = """
            event_id, source_id, external_product_id, listing_id, event_type, request_fingerprint,
            requested_version, applied_version, outcome, reason, ingestion_path, ingestion_job_id, correlation_id,
            occurred_at, received_at, processed_at""";

    /**
     * ON CONFLICT DO NOTHING turns the primary key into the final idempotency guard: if a concurrent
     * transaction recorded the same event id first, this insert waits for it to commit and then inserts
     * nothing, which the caller detects and resolves by retrying (and then seeing the duplicate).
     */
    private static final String INSERT_SQL = """
            INSERT INTO processed_events (
                event_id, source_id, external_product_id, listing_id, event_type, request_fingerprint,
                requested_version, applied_version, outcome, reason, ingestion_path, ingestion_job_id,
                correlation_id, occurred_at, received_at, processed_at)
            VALUES (
                :eventId, :sourceId, :externalId, :listingId, :eventType, :fingerprint,
                :requestedVersion, :appliedVersion, :outcome, :reason, :path, :jobId,
                :correlationId, :occurredAt, :receivedAt, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final RowMapper<ProcessedEventRecord> MAPPER = (rs, i) -> new ProcessedEventRecord(
            JdbcValues.uuid(rs, "event_id"),
            rs.getLong("source_id"),
            rs.getString("external_product_id"),
            rs.getLong("listing_id"),
            ListingEventType.valueOf(rs.getString("event_type")),
            rs.getString("request_fingerprint"),
            rs.getLong("requested_version"),
            JdbcValues.nullableLong(rs, "applied_version"),
            IngestionOutcome.valueOf(rs.getString("outcome")),
            rs.getString("reason"),
            IngestionPath.valueOf(rs.getString("ingestion_path")),
            JdbcValues.nullableLong(rs, "ingestion_job_id"),
            rs.getString("correlation_id"),
            JdbcValues.instant(rs, "occurred_at"),
            JdbcValues.instant(rs, "received_at"),
            JdbcValues.instant(rs, "processed_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public ProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProcessedEventRecord> find(UUID eventId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM processed_events WHERE event_id = :id",
                new MapSqlParameterSource("id", eventId), MAPPER).stream().findFirst();
    }

    /** @return {@code true} if the row was inserted, {@code false} if the event id already existed */
    public boolean insert(ProcessedEventRecord r) {
        int rows = jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                .addValue("eventId", r.eventId())
                .addValue("sourceId", r.sourceId())
                .addValue("externalId", r.externalProductId())
                .addValue("listingId", r.listingId())
                .addValue("eventType", r.eventType().name())
                .addValue("fingerprint", r.requestFingerprint())
                .addValue("requestedVersion", r.requestedVersion())
                .addValue("appliedVersion", r.appliedVersion(), Types.BIGINT)
                .addValue("outcome", r.outcome().name())
                .addValue("reason", JdbcValues.trimTo(r.reason(), 300))
                .addValue("path", r.ingestionPath().name())
                .addValue("jobId", r.ingestionJobId(), Types.BIGINT)
                .addValue("correlationId", JdbcValues.trimTo(r.correlationId(), 100), Types.VARCHAR)
                .addValue("occurredAt", JdbcValues.ts(r.occurredAt()))
                .addValue("receivedAt", JdbcValues.ts(r.receivedAt()))
                .addValue("processedAt", JdbcValues.ts(r.processedAt())));
        return rows == 1;
    }

    public List<ProcessedEventRecord> historyForListing(long listingId, int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM processed_events WHERE listing_id = :id "
                        + "ORDER BY processed_at DESC, event_id LIMIT :limit",
                new MapSqlParameterSource().addValue("id", listingId).addValue("limit", limit), MAPPER);
    }

    public boolean ingestionJobExists(long ingestionJobId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ingestion_jobs WHERE ingestion_job_id = :id",
                new MapSqlParameterSource("id", ingestionJobId), Integer.class);
        return n != null && n > 0;
    }
}
