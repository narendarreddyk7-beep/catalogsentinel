-- Top statements by total execution time for one database. Requires shared_preload_libraries =
-- 'pg_stat_statements' (server restart) and CREATE EXTENSION pg_stat_statements; reset with
-- SELECT pg_stat_statements_reset() before the run you want to profile.
--   psql -d <db> -f scripts/bench/pg-stat-statements.sql
SELECT calls,
       round((total_exec_time / 1000)::numeric, 1) AS total_s,
       round(mean_exec_time::numeric, 2) AS mean_ms,
       round((100 * total_exec_time / sum(total_exec_time) OVER ())::numeric, 1) AS pct,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT 16;
