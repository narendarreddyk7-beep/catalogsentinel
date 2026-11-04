-- Query plans for the statements the repair pass added or changed: match evidence currency (ADR 0013), reference
-- eligibility, durable reconciliation jobs (V6) and gaps following merges (ADR 0014). Run against a database loaded
-- by IngestionBenchmark with matching and one reconciliation (scripts/benchmark.sh, section "latency"):
--   psql -h 127.0.0.1 -U catalog -d catalogsentinel_bench -f scripts/bench/repair-plans.sql
-- Statements that write run inside transactions that are rolled back. The SQL mirrors the repositories; keep it in
-- step with MatchCandidateRepository, CanonicalProductRepository, GapRepository and ReconciliationJobRepository.
\set ON_ERROR_STOP on
\pset pager off

SELECT (SELECT count(*) FROM product_listings) AS listings,
       (SELECT count(*) FROM match_candidates) AS candidates,
       (SELECT count(*) FROM canonical_products) AS products,
       (SELECT count(*) FROM selection_gaps) AS gaps,
       (SELECT count(*) FROM reconciliation_jobs) AS jobs;

-- The listing with the most candidates: the worst case for per-listing candidate queries.
SELECT x.id AS busy_listing
FROM (SELECT left_listing_id AS id FROM match_candidates UNION ALL SELECT right_listing_id FROM match_candidates) x
GROUP BY x.id ORDER BY count(*) DESC, x.id LIMIT 1 \gset
SELECT source_id AS target FROM catalog_sources WHERE role = 'MARKETPLACE' ORDER BY source_id LIMIT 1 \gset
\echo busy listing :busy_listing, target source :target

\echo
\echo '== 1. MatchCandidateRepository.linkableFor: current, linkable candidates of one listing'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.*
FROM match_candidates m
JOIN product_listings la ON la.listing_id = m.left_listing_id
JOIN product_listings lb ON lb.listing_id = m.right_listing_id
WHERE (m.left_listing_id = :busy_listing OR m.right_listing_id = :busy_listing)
  AND (m.review_status = 'APPROVED' OR (m.decision = 'AUTO_MATCH' AND m.review_status = 'AUTO_ACCEPTED'))
  AND la.status = 'ACTIVE' AND lb.status = 'ACTIVE'
  AND la.content_hash = m.left_content_hash AND lb.content_hash = m.right_content_hash
ORDER BY (m.review_status = 'APPROVED') DESC, m.score DESC, m.candidate_id;

\echo
\echo '== 2. MatchCandidateRepository.supersedeStaleFor after the busy listing changed (rolled back)'
BEGIN;
UPDATE product_listings SET content_hash = encode(sha256(convert_to('changed ' || listing_id, 'UTF8')), 'hex')
WHERE listing_id = :busy_listing;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH stale AS (
    SELECT m.candidate_id, m.review_status AS previous_status, m.reviewed_by AS previous_reviewer,
           m.left_listing_id, m.right_listing_id
    FROM match_candidates m
    JOIN product_listings la ON la.listing_id = m.left_listing_id
    JOIN product_listings lb ON lb.listing_id = m.right_listing_id
    WHERE (m.left_listing_id = :busy_listing OR m.right_listing_id = :busy_listing)
      AND m.review_status <> 'SUPERSEDED'
      AND (la.status <> 'ACTIVE' OR lb.status <> 'ACTIVE'
           OR la.content_hash IS DISTINCT FROM m.left_content_hash
           OR lb.content_hash IS DISTINCT FROM m.right_content_hash)
    FOR UPDATE OF m
), superseded AS (
    UPDATE match_candidates m SET review_status = 'SUPERSEDED', reviewed_by = NULL, reviewed_at = NULL
    FROM stale WHERE m.candidate_id = stale.candidate_id
    RETURNING m.candidate_id
)
SELECT s.candidate_id FROM stale s JOIN superseded u ON u.candidate_id = s.candidate_id ORDER BY s.candidate_id;
ROLLBACK;

\echo
\echo '== 3. CanonicalProductRepository.findMergeablePairs (merge pass before every reconciliation)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT DISTINCT LEAST(a.canonical_product_id, b.canonical_product_id) AS keep_id,
                GREATEST(a.canonical_product_id, b.canonical_product_id) AS merge_id
FROM match_candidates m
JOIN product_listings la ON la.listing_id = m.left_listing_id
JOIN product_listings lb ON lb.listing_id = m.right_listing_id
JOIN listing_product_links ka ON ka.listing_id = m.left_listing_id AND ka.linked_content_hash = la.content_hash
JOIN listing_product_links kb ON kb.listing_id = m.right_listing_id AND kb.linked_content_hash = lb.content_hash
JOIN canonical_products a ON a.canonical_product_id = ka.canonical_product_id AND a.status = 'ACTIVE'
JOIN canonical_products b ON b.canonical_product_id = kb.canonical_product_id AND b.status = 'ACTIVE'
WHERE a.canonical_product_id <> b.canonical_product_id
  AND (m.review_status = 'APPROVED' OR (m.decision = 'AUTO_MATCH' AND m.review_status = 'AUTO_ACCEPTED'))
  AND la.status = 'ACTIVE' AND lb.status = 'ACTIVE'
  AND la.content_hash = m.left_content_hash AND lb.content_hash = m.right_content_hash
ORDER BY keep_id, merge_id
LIMIT 100;

\echo
\echo '== 4. GapRepository.referenceProducts: one keyset batch of 200 with the eligibility predicate'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT c.canonical_product_id,
       array_agg(DISTINCT l.listing_id ORDER BY l.listing_id)
           FILTER (WHERE s.role = 'REFERENCE' AND s.enabled AND l.status = 'ACTIVE') AS reference_listings,
       count(DISTINCT l.source_id) FILTER (WHERE s.role = 'REFERENCE' AND s.enabled AND l.status = 'ACTIVE') AS sources,
       min(l.listing_id) FILTER (WHERE l.source_id = :target AND l.status = 'ACTIVE') AS covering_listing
FROM canonical_products c
JOIN listing_product_links k ON k.canonical_product_id = c.canonical_product_id
JOIN product_listings l ON l.listing_id = k.listing_id
JOIN catalog_sources s ON s.source_id = l.source_id
WHERE c.status = 'ACTIVE' AND c.canonical_product_id > 0
GROUP BY c.canonical_product_id
HAVING count(*) FILTER (WHERE s.role = 'REFERENCE' AND s.enabled AND l.status = 'ACTIVE') > 0
ORDER BY c.canonical_product_id
LIMIT 200;

\echo
\echo '== 5. GapRepository.bestCandidate: closest current candidate in the target for one product'
SELECT array_agg(k.listing_id) AS refs
FROM listing_product_links k JOIN product_listings l ON l.listing_id = k.listing_id
JOIN catalog_sources s ON s.source_id = l.source_id AND s.role = 'REFERENCE'
WHERE k.canonical_product_id = (SELECT canonical_product_id FROM selection_gaps ORDER BY gap_id LIMIT 1)
\gset
\echo reference listings :refs
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT m.candidate_id, t.listing_id AS target_listing, m.score, m.decision, m.review_status
FROM match_candidates m
JOIN product_listings la ON la.listing_id = m.left_listing_id
JOIN product_listings lb ON lb.listing_id = m.right_listing_id
JOIN product_listings t
  ON t.listing_id = CASE WHEN m.left_listing_id = ANY (:'refs'::bigint[]) THEN m.right_listing_id ELSE m.left_listing_id END
WHERE (m.left_listing_id = ANY (:'refs'::bigint[]) OR m.right_listing_id = ANY (:'refs'::bigint[]))
  AND t.source_id = :target AND t.status = 'ACTIVE'
  AND m.review_status <> 'SUPERSEDED'
  AND la.status = 'ACTIVE' AND lb.status = 'ACTIVE'
  AND la.content_hash = m.left_content_hash AND lb.content_hash = m.right_content_hash
ORDER BY m.score DESC, m.candidate_id
LIMIT 1;

\echo
\echo '== 6. GapRepository.invalidatedGaps: live gaps whose product no longer qualifies'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT gap_id, resolution FROM (
    SELECT g.gap_id,
           CASE WHEN c.status = 'MERGED' THEN 'MERGED_PRODUCT'
                WHEN NOT eligible.any_listing THEN 'REFERENCE_WITHDRAWN'
                WHEN NOT eligible.enabled_listing THEN 'REFERENCE_SOURCE_DISABLED'
           END AS resolution
    FROM selection_gaps g
    JOIN canonical_products c ON c.canonical_product_id = g.canonical_product_id
    CROSS JOIN LATERAL (
        SELECT coalesce(bool_or(true), false) AS any_listing, coalesce(bool_or(s.enabled), false) AS enabled_listing
        FROM listing_product_links k
        JOIN product_listings l ON l.listing_id = k.listing_id
        JOIN catalog_sources s ON s.source_id = l.source_id
        WHERE k.canonical_product_id = g.canonical_product_id AND l.status = 'ACTIVE' AND s.role = 'REFERENCE'
    ) eligible
    WHERE g.target_source_id = :target AND g.status IN ('OPEN', 'CONFIRMED')
) x
WHERE resolution IS NOT NULL
ORDER BY gap_id;

\echo
\echo '== 7. GapRepository.liveGapsOnMergedProducts with 500 products merged away (rolled back)'
BEGIN;
WITH victims AS (
    SELECT g.canonical_product_id AS id, row_number() OVER (ORDER BY g.canonical_product_id) AS n
    FROM selection_gaps g WHERE g.target_source_id = :target AND g.status = 'OPEN'
    ORDER BY g.canonical_product_id LIMIT 500
)
UPDATE canonical_products c SET status = 'MERGED',
       merged_into_id = (SELECT max(canonical_product_id) FROM canonical_products)
FROM victims v WHERE c.canonical_product_id = v.id;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH RECURSIVE chain AS (
    SELECT g.gap_id, g.status, g.updated_at, g.canonical_product_id AS merged_product,
           c.merged_into_id AS product, 1 AS depth
    FROM selection_gaps g
    JOIN canonical_products c ON c.canonical_product_id = g.canonical_product_id
    WHERE g.target_source_id = :target AND g.status IN ('OPEN', 'CONFIRMED', 'DISMISSED')
      AND c.status = 'MERGED' AND c.merged_into_id IS NOT NULL
    UNION ALL
    SELECT ch.gap_id, ch.status, ch.updated_at, ch.merged_product, c.merged_into_id, ch.depth + 1
    FROM chain ch
    JOIN canonical_products c ON c.canonical_product_id = ch.product
    WHERE c.status = 'MERGED' AND c.merged_into_id IS NOT NULL AND ch.depth < 64
)
SELECT gap_id, status, merged_product, product AS survivor FROM (
    SELECT DISTINCT ON (gap_id) gap_id, status, updated_at, merged_product, product
    FROM chain ORDER BY gap_id, depth DESC
) last
ORDER BY (status <> 'OPEN') DESC, updated_at DESC, gap_id;
ROLLBACK;

\echo
\echo '== 8. ReconciliationJobRepository.claim among 20,000 finished jobs and 1 due job (rolled back)'
BEGIN;
INSERT INTO reconciliation_jobs (target_source_id, status, requested_by, created_at, started_at, finished_at)
SELECT :target, 'SUCCEEDED', 'bench', now() - make_interval(mins => g), now() - make_interval(mins => g),
       now() - make_interval(mins => g)
FROM generate_series(1, 20000) g;
INSERT INTO reconciliation_jobs (target_source_id, status, requested_by) VALUES (:target, 'PENDING', 'bench');
ANALYZE reconciliation_jobs;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
WITH next AS (
    SELECT job_id FROM reconciliation_jobs
    WHERE ((status = 'PENDING' AND next_attempt_at <= now()) OR (status = 'RUNNING' AND lease_expires_at < now()))
      AND attempt < max_attempts
    ORDER BY job_id
    LIMIT 1
    FOR UPDATE SKIP LOCKED
)
UPDATE reconciliation_jobs j
SET status = 'RUNNING', attempt = j.attempt + 1, lease_owner = 'bench', lease_expires_at = now() + interval '5 minutes',
    started_at = now(), finished_at = NULL
FROM next WHERE j.job_id = next.job_id
RETURNING j.job_id;
ROLLBACK;
