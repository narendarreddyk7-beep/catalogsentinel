-- Title-trigram candidate query (MatchingRepository) with literal values, i.e. custom plans, for a random sample
-- of 300 active listings under random_page_cost 4 and 1.1. Prints plan counts and latency per setting, then the
-- listings whose query used a sequential scan at 4 (use one as :'broad_title' in generic-plan.sql).
--   psql -d <db> -f scripts/bench/trigram-plan-sample.sql
BEGIN;
CREATE TEMP TABLE plan_sample (listing_id bigint, rpc text, plan text, ms numeric) ON COMMIT DROP;
DO $$
DECLARE
    r record; rpc text; line text; t numeric; plan text;
BEGIN
    PERFORM setseed(0.42);
    FOR r IN SELECT listing_id, normalized_title FROM product_listings WHERE status = 'ACTIVE'
             ORDER BY random() LIMIT 300 LOOP
        FOREACH rpc IN ARRAY ARRAY['4', '1.1'] LOOP
            EXECUTE format('SET LOCAL random_page_cost = %s', rpc);
            plan := NULL; t := NULL;
            FOR line IN EXECUTE format($q$EXPLAIN (ANALYZE) SELECT listing_id, similarity(normalized_title, %L) AS sim
                    FROM product_listings WHERE normalized_title %% %L AND status = 'ACTIVE' AND listing_id <> %s
                    ORDER BY sim DESC, listing_id LIMIT 10$q$, r.normalized_title, r.normalized_title, r.listing_id) LOOP
                IF plan IS NULL AND line ~ '(Bitmap Index Scan|Seq Scan)' THEN
                    plan := CASE WHEN line ~ 'Bitmap Index Scan' THEN 'gin' ELSE 'seq' END;
                END IF;
                IF line ~ '^Execution Time' THEN
                    t := substring(line FROM '([0-9.]+) ms')::numeric;
                END IF;
            END LOOP;
            INSERT INTO plan_sample VALUES (r.listing_id, rpc, plan, t);
        END LOOP;
    END LOOP;
END $$;
SELECT rpc AS random_page_cost, count(*) AS queries, count(*) FILTER (WHERE plan = 'seq') AS seq_plans,
       round(avg(ms), 1) AS mean_ms,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric, 1) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1) AS p95_ms,
       round(sum(ms) / 1000, 1) AS total_s
FROM plan_sample GROUP BY rpc ORDER BY rpc DESC;
SELECT s.listing_id, l.normalized_title FROM plan_sample s JOIN product_listings l USING (listing_id)
WHERE s.rpc = '4' AND s.plan = 'seq' ORDER BY s.ms DESC LIMIT 5;
COMMIT;
