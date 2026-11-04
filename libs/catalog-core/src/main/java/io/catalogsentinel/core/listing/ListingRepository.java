package io.catalogsentinel.core.listing;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.domain.listing.ListingState;
import io.catalogsentinel.domain.listing.ListingStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Write-side persistence for listings, used only inside the ingestion transaction.
 *
 * <p>Concurrency model: callers lock the listing row with {@code SELECT ... FOR UPDATE} before deciding, so
 * concurrent events for the same listing are serialized and each decision sees the latest committed version
 * (PostgreSQL re-reads the row after a lock wait under READ COMMITTED). Creation races are resolved by the
 * unique constraint: {@code INSERT ... ON CONFLICT DO NOTHING} returns no id when another transaction won,
 * and the caller then locks the winner's row and decides again.
 */
@Repository
public class ListingRepository {

    private static final String LOCK_SQL = """
            SELECT listing_id, source_version, status, content_hash
            FROM product_listings
            WHERE source_id = :sourceId AND external_product_id = :externalId
            FOR UPDATE
            """;

    private static final String INSERT_SQL = """
            INSERT INTO product_listings (
                source_id, external_product_id, status, source_version,
                title, brand, model, category, gtin, attributes,
                normalized_title, normalized_brand, normalized_model, normalized_category, category_leaf,
                extracted_attributes, normalization_version, content_hash,
                last_event_id, source_updated_at, withdrawn_at)
            VALUES (
                :sourceId, :externalId, :status, :version,
                :title, :brand, :model, :category, :gtin, CAST(:attributes AS jsonb),
                :normalizedTitle, :normalizedBrand, :normalizedModel, :normalizedCategory, :categoryLeaf,
                CAST(:extractedAttributes AS jsonb), :normalizationVersion, :contentHash,
                :eventId, :occurredAt, :withdrawnAt)
            ON CONFLICT (source_id, external_product_id) DO NOTHING
            RETURNING listing_id
            """;

    private static final String UPDATE_STATE_SQL = """
            UPDATE product_listings SET
                status = :status,
                source_version = :version,
                withdrawn_at = CASE WHEN :status = 'WITHDRAWN' THEN COALESCE(withdrawn_at, :now) ELSE NULL END,
                last_event_id = :eventId,
                source_updated_at = :occurredAt
            WHERE listing_id = :listingId
            """;

    private static final String UPDATE_STATE_AND_CONTENT_SQL = """
            UPDATE product_listings SET
                status = :status,
                source_version = :version,
                withdrawn_at = CASE WHEN :status = 'WITHDRAWN' THEN COALESCE(withdrawn_at, :now) ELSE NULL END,
                last_event_id = :eventId,
                source_updated_at = :occurredAt,
                title = :title,
                brand = :brand,
                model = :model,
                category = :category,
                gtin = :gtin,
                attributes = CAST(:attributes AS jsonb),
                normalized_title = :normalizedTitle,
                normalized_brand = :normalizedBrand,
                normalized_model = :normalizedModel,
                normalized_category = :normalizedCategory,
                category_leaf = :categoryLeaf,
                extracted_attributes = CAST(:extractedAttributes AS jsonb),
                normalization_version = :normalizationVersion,
                content_hash = :contentHash
            WHERE listing_id = :listingId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ListingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the listing row for the rest of the current transaction. Must run inside a transaction. */
    public Optional<LockedListing> lockForUpdate(long sourceId, String externalProductId) {
        List<LockedListing> rows = jdbc.query(LOCK_SQL,
                new MapSqlParameterSource().addValue("sourceId", sourceId).addValue("externalId", externalProductId),
                (rs, i) -> new LockedListing(
                        rs.getLong("listing_id"),
                        new ListingState(rs.getLong("source_version"),
                                ListingStatus.valueOf(rs.getString("status")),
                                rs.getString("content_hash"))));
        return rows.stream().findFirst();
    }

    /** Inserts a new listing row; returns empty if a concurrent transaction created it first. */
    public Optional<Long> insertIfAbsent(ListingWrite write) {
        List<Long> ids = jdbc.query(INSERT_SQL, params(write, null), (rs, i) -> rs.getLong(1));
        return ids.stream().findFirst();
    }

    public void update(long listingId, ListingWrite write) {
        String sql = write.hasContent() ? UPDATE_STATE_AND_CONTENT_SQL : UPDATE_STATE_SQL;
        int updated = jdbc.update(sql, params(write, listingId));
        if (updated != 1) {
            throw new IllegalStateException("expected to update exactly one listing row, updated " + updated);
        }
    }

    private static MapSqlParameterSource params(ListingWrite w, Long listingId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sourceId", w.sourceId())
                .addValue("externalId", w.externalProductId())
                .addValue("status", w.status().name())
                .addValue("version", w.version())
                .addValue("eventId", w.eventId())
                .addValue("occurredAt", JdbcValues.ts(w.occurredAt()))
                .addValue("now", JdbcValues.ts(w.now()))
                .addValue("withdrawnAt", w.status() == ListingStatus.WITHDRAWN ? JdbcValues.ts(w.now()) : null,
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("title", w.title(), Types.VARCHAR)
                .addValue("brand", w.brand(), Types.VARCHAR)
                .addValue("model", w.model(), Types.VARCHAR)
                .addValue("category", w.category(), Types.VARCHAR)
                .addValue("gtin", w.gtin(), Types.VARCHAR)
                .addValue("attributes", w.attributesJson() == null ? "{}" : w.attributesJson())
                .addValue("normalizedTitle", w.normalizedTitle(), Types.VARCHAR)
                .addValue("normalizedBrand", w.normalizedBrand(), Types.VARCHAR)
                .addValue("normalizedModel", w.normalizedModel(), Types.VARCHAR)
                .addValue("normalizedCategory", w.normalizedCategory(), Types.VARCHAR)
                .addValue("categoryLeaf", w.categoryLeaf(), Types.VARCHAR)
                .addValue("extractedAttributes", w.extractedAttributesJson() == null ? "{}" : w.extractedAttributesJson())
                .addValue("normalizationVersion", w.normalizationVersion(), Types.VARCHAR)
                .addValue("contentHash", w.contentHash(), Types.VARCHAR);
        if (listingId != null) {
            p.addValue("listingId", listingId);
        }
        return p;
    }

    /** A listing row locked by the current transaction. */
    public record LockedListing(long listingId, ListingState state) {
    }

    /**
     * Column values for an insert or update. Content fields are {@code null} when the event carries no
     * content (withdrawals); {@link #hasContent()} then tells the update to leave stored content untouched.
     */
    public record ListingWrite(
            long sourceId,
            String externalProductId,
            ListingStatus status,
            long version,
            UUID eventId,
            Instant occurredAt,
            Instant now,
            String title,
            String brand,
            String model,
            String category,
            String gtin,
            String attributesJson,
            String normalizedTitle,
            String normalizedBrand,
            String normalizedModel,
            String normalizedCategory,
            String categoryLeaf,
            String extractedAttributesJson,
            String normalizationVersion,
            String contentHash) {

        public boolean hasContent() {
            return contentHash != null;
        }
    }
}
