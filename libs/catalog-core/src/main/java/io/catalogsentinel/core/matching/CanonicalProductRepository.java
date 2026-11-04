package io.catalogsentinel.core.matching;

import io.catalogsentinel.core.common.JdbcValues;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static io.catalogsentinel.core.matching.MatchCandidateRepository.CURRENT;
import static io.catalogsentinel.core.matching.MatchCandidateRepository.JOIN_LISTINGS;
import static io.catalogsentinel.core.matching.MatchCandidateRepository.LINKABLE;

/** Canonical product identities and the listing-to-product links. */
@Repository
public class CanonicalProductRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CanonicalProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a canonical product described by (and represented by) the given listing. */
    public long createFrom(MatchableListing l) {
        return jdbc.queryForObject("""
                INSERT INTO canonical_products (title, brand, model, category, gtin, normalized_brand, normalized_model,
                                                category_leaf, extracted_attributes, representative_listing_id)
                VALUES (:title, :brand, :model, :category, :gtin, :nBrand, :nModel, :leaf, CAST(:attrs AS jsonb), :rep)
                RETURNING canonical_product_id
                """, new MapSqlParameterSource()
                .addValue("title", l.title())
                .addValue("brand", l.brand(), Types.VARCHAR)
                .addValue("model", l.model(), Types.VARCHAR)
                .addValue("category", l.category(), Types.VARCHAR)
                .addValue("gtin", l.gtin(), Types.VARCHAR)
                .addValue("nBrand", l.features().brand(), Types.VARCHAR)
                .addValue("nModel", l.features().model(), Types.VARCHAR)
                .addValue("leaf", l.features().categoryLeaf(), Types.VARCHAR)
                .addValue("attrs", l.extractedAttributesJson() == null ? "{}" : l.extractedAttributesJson())
                .addValue("rep", l.listingId()), Long.class);
    }

    /** Refreshes descriptive fields when the representative listing's content changed. */
    public void refreshFromRepresentative(long canonicalProductId, MatchableListing l) {
        jdbc.update("""
                UPDATE canonical_products SET title = :title, brand = :brand, model = :model, category = :category,
                       gtin = COALESCE(:gtin, gtin), normalized_brand = :nBrand, normalized_model = :nModel,
                       category_leaf = :leaf, extracted_attributes = CAST(:attrs AS jsonb)
                WHERE canonical_product_id = :id AND representative_listing_id = :rep
                """, new MapSqlParameterSource()
                .addValue("id", canonicalProductId)
                .addValue("title", l.title())
                .addValue("brand", l.brand(), Types.VARCHAR)
                .addValue("model", l.model(), Types.VARCHAR)
                .addValue("category", l.category(), Types.VARCHAR)
                .addValue("gtin", l.gtin(), Types.VARCHAR)
                .addValue("nBrand", l.features().brand(), Types.VARCHAR)
                .addValue("nModel", l.features().model(), Types.VARCHAR)
                .addValue("leaf", l.features().categoryLeaf(), Types.VARCHAR)
                .addValue("attrs", l.extractedAttributesJson() == null ? "{}" : l.extractedAttributesJson())
                .addValue("rep", l.listingId()));
    }

    public Optional<Link> findLink(long listingId) {
        return jdbc.query("""
                SELECT k.listing_id, k.canonical_product_id, k.confidence, k.match_method, k.review_status,
                       k.via_candidate_id, k.linked_content_hash, c.representative_listing_id
                FROM listing_product_links k JOIN canonical_products c ON c.canonical_product_id = k.canonical_product_id
                WHERE k.listing_id = :id
                """, new MapSqlParameterSource("id", listingId), (rs, i) -> new Link(rs.getLong("listing_id"),
                rs.getLong("canonical_product_id"), rs.getBigDecimal("confidence").doubleValue(),
                rs.getString("match_method"), rs.getString("review_status"),
                JdbcValues.nullableLong(rs, "via_candidate_id"), rs.getString("linked_content_hash"),
                JdbcValues.nullableLong(rs, "representative_listing_id"))).stream().findFirst();
    }

    /** Follows merge pointers to the surviving (ACTIVE) canonical product. */
    public long resolveActive(long canonicalProductId) {
        return jdbc.queryForObject("""
                WITH RECURSIVE chain AS (
                    SELECT canonical_product_id, merged_into_id, status, 0 AS depth
                    FROM canonical_products WHERE canonical_product_id = :id
                    UNION ALL
                    SELECT c.canonical_product_id, c.merged_into_id, c.status, chain.depth + 1
                    FROM canonical_products c JOIN chain ON c.canonical_product_id = chain.merged_into_id
                    WHERE chain.depth < 32
                )
                SELECT canonical_product_id FROM chain WHERE status = 'ACTIVE' ORDER BY depth LIMIT 1
                """, new MapSqlParameterSource("id", canonicalProductId), Long.class);
    }

    public void upsertLink(long listingId, long canonicalProductId, double confidence, String method,
                           String reviewStatus, String matcherVersion, Long viaCandidateId, String contentHash) {
        jdbc.update("""
                INSERT INTO listing_product_links (listing_id, canonical_product_id, confidence, match_method,
                                                   review_status, matcher_version, via_candidate_id, linked_content_hash)
                VALUES (:listingId, :canonicalId, :confidence, :method, :reviewStatus, :matcherVersion, :via, :hash)
                ON CONFLICT (listing_id) DO UPDATE SET
                    canonical_product_id = EXCLUDED.canonical_product_id,
                    confidence = EXCLUDED.confidence,
                    match_method = EXCLUDED.match_method,
                    review_status = EXCLUDED.review_status,
                    matcher_version = EXCLUDED.matcher_version,
                    via_candidate_id = EXCLUDED.via_candidate_id,
                    linked_content_hash = EXCLUDED.linked_content_hash
                """, new MapSqlParameterSource()
                .addValue("listingId", listingId)
                .addValue("canonicalId", canonicalProductId)
                .addValue("confidence", JdbcValues.score(confidence))
                .addValue("method", method)
                .addValue("reviewStatus", reviewStatus)
                .addValue("matcherVersion", matcherVersion)
                .addValue("via", viaCandidateId, Types.BIGINT)
                .addValue("hash", contentHash, Types.VARCHAR));
    }

    /**
     * Current links on both sides of a current linkable candidate: the candidate describes both listings' present
     * content, and each listing's link was decided on that same content. Stale evidence or a listing whose change
     * has not been re-matched yet can never join two products (docs/adr/0013-match-evidence-currency.md).
     */
    private static final String CURRENT_EVIDENCE_BETWEEN_LINKS = "FROM match_candidates m " + JOIN_LISTINGS + """

            JOIN listing_product_links ka ON ka.listing_id = m.left_listing_id AND ka.linked_content_hash = la.content_hash
            JOIN listing_product_links kb ON kb.listing_id = m.right_listing_id AND kb.linked_content_hash = lb.content_hash
            """;

    /**
     * Pairs of distinct ACTIVE canonical products joined by current evidence: duplicates created when two matching
     * listings were first seen concurrently, or a new approval. Ordered so merges are deterministic.
     */
    public List<long[]> findMergeablePairs(int limit) {
        return jdbc.query("""
                SELECT DISTINCT LEAST(a.canonical_product_id, b.canonical_product_id) AS keep_id,
                                GREATEST(a.canonical_product_id, b.canonical_product_id) AS merge_id
                """ + CURRENT_EVIDENCE_BETWEEN_LINKS + """
                JOIN canonical_products a ON a.canonical_product_id = ka.canonical_product_id AND a.status = 'ACTIVE'
                JOIN canonical_products b ON b.canonical_product_id = kb.canonical_product_id AND b.status = 'ACTIVE'
                WHERE a.canonical_product_id <> b.canonical_product_id AND %s AND %s
                ORDER BY keep_id, merge_id
                LIMIT :limit
                """.formatted(LINKABLE, CURRENT), new MapSqlParameterSource("limit", limit),
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    /**
     * Merges {@code mergeId} into {@code keepId}: relinks listings and marks the merged product. Re-checks under
     * the product locks that current evidence still joins them (it may have gone stale since discovery); returns 0
     * without merging if not.
     */
    public int merge(long keepId, long mergeId) {
        if (lockActiveProducts(List.of(keepId, mergeId)).size() != 2) {
            return 0; // one side was merged concurrently; the next pass will pick up the new pair
        }
        Boolean stillJoined = jdbc.queryForObject("SELECT EXISTS (SELECT 1 " + CURRENT_EVIDENCE_BETWEEN_LINKS + """
                 WHERE %s AND %s
                  AND ((ka.canonical_product_id = :a AND kb.canonical_product_id = :b)
                    OR (ka.canonical_product_id = :b AND kb.canonical_product_id = :a)))
                """.formatted(LINKABLE, CURRENT), new MapSqlParameterSource().addValue("a", keepId).addValue("b", mergeId),
                Boolean.class);
        if (!Boolean.TRUE.equals(stillJoined)) {
            return 0;
        }
        int relinked = jdbc.update("""
                UPDATE listing_product_links SET canonical_product_id = :keep, match_method = 'MERGED'
                WHERE canonical_product_id = :merge
                """, new MapSqlParameterSource().addValue("keep", keepId).addValue("merge", mergeId));
        jdbc.update("""
                UPDATE canonical_products SET status = 'MERGED', merged_into_id = :keep WHERE canonical_product_id = :merge
                """, new MapSqlParameterSource().addValue("keep", keepId).addValue("merge", mergeId));
        return relinked;
    }

    /**
     * Locks the given products that are still ACTIVE, in id order (the global lock order for product rows, so
     * concurrent re-links, splits and merges cannot deadlock), and returns their ids.
     */
    public List<Long> lockActiveProducts(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT canonical_product_id FROM canonical_products
                WHERE canonical_product_id IN (:ids) AND status = 'ACTIVE'
                ORDER BY canonical_product_id FOR UPDATE
                """, new MapSqlParameterSource("ids", productIds), (rs, i) -> rs.getLong(1));
    }

    /** ACTIVE listings linked to the product, in id order, with how each link was justified. */
    public List<Member> activeMembers(long productId) {
        return jdbc.query("""
                SELECT k.listing_id, k.match_method, k.review_status, k.via_candidate_id, k.linked_content_hash,
                       l.content_hash
                FROM listing_product_links k JOIN product_listings l ON l.listing_id = k.listing_id
                WHERE k.canonical_product_id = :p AND l.status = 'ACTIVE'
                ORDER BY k.listing_id
                """, new MapSqlParameterSource("p", productId), (rs, i) -> new Member(rs.getLong("listing_id"),
                rs.getString("match_method"), rs.getString("review_status"),
                JdbcValues.nullableLong(rs, "via_candidate_id"), rs.getString("linked_content_hash"),
                rs.getString("content_hash")));
    }

    public Optional<Long> representativeOf(long productId) {
        return jdbc.query("SELECT representative_listing_id FROM canonical_products WHERE canonical_product_id = :p",
                new MapSqlParameterSource("p", productId), (rs, i) -> JdbcValues.nullableLong(rs, "representative_listing_id"))
                .stream().findFirst();
    }

    /** Makes the listing the product's representative; call {@link #refreshFromRepresentative} afterwards. */
    public void setRepresentative(long productId, long listingId) {
        jdbc.update("UPDATE canonical_products SET representative_listing_id = :rep WHERE canonical_product_id = :p",
                new MapSqlParameterSource().addValue("p", productId).addValue("rep", listingId));
    }

    /** One ACTIVE listing linked to a product. {@code linkedContentHash} is the content its link was decided on. */
    public record Member(long listingId, String matchMethod, String reviewStatus, Long viaCandidateId,
                         String linkedContentHash, String contentHash) {
    }

    public record Link(long listingId, long canonicalProductId, double confidence, String matchMethod,
                       String reviewStatus, Long viaCandidateId, String linkedContentHash, Long representativeListingId) {
    }
}
