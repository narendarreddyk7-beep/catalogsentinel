-- Shows why random_page_cost does not change the application's matching time: after five executions of a
-- prepared statement PostgreSQL may switch to a generic plan, which for this query uses the GIN index for every
-- title. Pass a broad title (one that got a sequential scan in trigram-plan-sample.sql):
--   psql -d <db> -v broad_title='...' -f scripts/bench/generic-plan.sql
SET random_page_cost = 4;
PREPARE trigram(text, bigint, int) AS
    SELECT listing_id, similarity(normalized_title, $1) AS sim FROM product_listings
    WHERE normalized_title % $1 AND status = 'ACTIVE' AND listing_id <> $2
    ORDER BY sim DESC, listing_id LIMIT $3;
-- Five executions with other titles (custom plans).
SELECT normalized_title AS t1 FROM product_listings WHERE status = 'ACTIVE' ORDER BY listing_id OFFSET 100 LIMIT 1 \gset
SELECT normalized_title AS t2 FROM product_listings WHERE status = 'ACTIVE' ORDER BY listing_id OFFSET 2000 LIMIT 1 \gset
SELECT normalized_title AS t3 FROM product_listings WHERE status = 'ACTIVE' ORDER BY listing_id OFFSET 5000 LIMIT 1 \gset
SELECT normalized_title AS t4 FROM product_listings WHERE status = 'ACTIVE' ORDER BY listing_id OFFSET 9000 LIMIT 1 \gset
SELECT normalized_title AS t5 FROM product_listings WHERE status = 'ACTIVE' ORDER BY listing_id OFFSET 13000 LIMIT 1 \gset
\o /dev/null
EXECUTE trigram(:'t1', 0, 10);
EXECUTE trigram(:'t2', 0, 10);
EXECUTE trigram(:'t3', 0, 10);
EXECUTE trigram(:'t4', 0, 10);
EXECUTE trigram(:'t5', 0, 10);
\o
-- The sixth execution: look for "$1" in the conditions (generic plan) and a Bitmap Index Scan.
EXPLAIN (ANALYZE) EXECUTE trigram(:'broad_title', 0, 10);
SELECT generic_plans, custom_plans FROM pg_prepared_statements WHERE name = 'trigram';
