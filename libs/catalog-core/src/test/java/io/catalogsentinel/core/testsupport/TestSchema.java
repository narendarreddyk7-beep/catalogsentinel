package io.catalogsentinel.core.testsupport;

/** SQL shared by test-database implementations. */
public final class TestSchema {

    /**
     * Truncates all application tables. The audit table's append-only triggers are disabled for the duration
     * of the statement (the test role owns the table, so it may do this; the application role in production
     * does not own tables — see docs/security.md).
     */
    public static final String RESET_SQL = """
            ALTER TABLE review_decisions DISABLE TRIGGER USER;
            TRUNCATE review_decisions, selection_gaps, reconciliation_jobs, listing_product_links, match_candidates,
                     canonical_products, outbox_events, processed_events, ingestion_jobs, product_listings,
                     catalog_sources
                RESTART IDENTITY CASCADE;
            ALTER TABLE review_decisions ENABLE TRIGGER USER;
            """;

    private TestSchema() {
    }
}
