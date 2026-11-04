# Benchmarking

Every figure in this document was measured on the machine below. Nothing is extrapolated: where something was
not measured, the document says so. `scripts/benchmark.sh` re-runs the generator, matching-quality and Spark
sections; the database measurements use the helpers in `scripts/bench/` named in each section.

## Machine

| | |
|---|---|
| CPU | 2 vCPU, Intel Xeon @ 2.10 GHz (cloud sandbox, shared host) |
| Memory | 8 GiB |
| JVM | OpenJDK 21 |
| Python / Spark | CPython 3.13, PySpark 4.2.1, `local[2]`, driver memory 2 GiB |
| PostgreSQL | 16.15 on the same machine; default configuration, except that the planner A/B set `random_page_cost` per database and the profiled run loaded `pg_stat_statements` |

A shared 2-vCPU sandbox is noisy: identical runs differed by up to 65 % (see the ingestion section), so
compare ranges, not single runs. These are development-machine figures, not production capacity numbers.

## Matching quality

See [benchmarks/matching/README.md](benchmarks/matching/README.md) for precision/recall of every matcher version
on the golden and synthetic sets, with the bias of each dataset.

## Gap detection quality

`GapReconciliationIT.detectsGapsAgainstGroundTruthAndIsIdempotent` reconciles the golden catalog (target
`acme-market`, `rules-v4`, `gap-v1`) and prints its evaluation against the hand-labeled truth:

| | gaps flagged | precision | recall |
|---|---|---|---|
| All open gaps | 54 | 0.352 | 1.000 (19 of 19 true gaps) |
| Confident gaps (confidence ≥ 0.5) | 18 | 0.778 | 0.737 |

Every true gap is surfaced; the open list deliberately also contains low-confidence gaps whose closest candidate
awaits match review, and confidence is what separates them. The remaining confident false gaps come from matcher
false negatives (the marketplace carries the product, but the matcher did not find it) and one deliberately wrong
GTIN in the golden set. The test
asserts floors below these values (recall 1.0, confident precision ≥ 0.75, confident recall ≥ 0.65). It is
80 reference products: a regression check, not an accuracy estimate for real catalogs.

## Synthetic data generation

`python3 scripts/generate-synthetic-data.py --size <size> --out <dir> --seed 7`

| size flag | listings written | products | true gaps | wall time | peak RSS |
|---|---|---|---|---|---|
| 10k | 10,066 | 5,076 | 1,114 | 0.16 s | not measured |
| 100k | 99,597 | 50,761 | 11,460 | 2.1 s | 182 MiB |
| 1m | 998,105 | 507,614 | 114,314 | 23.8 s | 1,483 MiB |

## Batch pipeline (Spark stages)

Dry-run mode (`--dry-run`): parse, validate, normalize, in-export dedup, write curated Parquet and rejected JSONL;
no API calls and no event publishing, so the numbers isolate the Spark work.

| export | records | rejected | parse + validate + normalize + dedup | write outputs | total | records/s |
|---|---|---|---|---|---|---|
| 100k / northwind-supply (1 file) | 42,992 | 0 | 16.6 s | 3.7 s | 20.5 s | ~2,100 |
| 100k / acme-market (1 file) | 38,758 | 128 | 16.2 s | 4.3 s | 20.7 s | ~1,870 |
| 1m / northwind-supply (9 files) | 431,719 | 0 | 73.0 s | 10.4 s | 83.9 s | ~5,150 |

Throughput rises with input size because fixed Spark job overhead (several actions: counts, dedup, writes) is
amortised and the 9-file export gives both cores work for the whole run. The hot path is the per-record Python
normalization (35 regular-expression passes per title); a production run would scale it with executors.

### Event publishing

Publishing was only exercised against the moto SQS emulator, whose single-process server accepted roughly 75
events/s from two concurrent partitions (4,368 events in 58.5 s) and ~270 messages/s from one sequential client.
That measures the emulator, not Amazon SQS, and is not a throughput claim for the pipeline.

## Ingestion engine and matching against PostgreSQL

`IngestionBenchmark` (libs/catalog-core test sources) drives the real `ListingIngestionService` and
`MatchingService` against the local PostgreSQL 16 through a HikariCP pool, the same code the worker runs, minus
SQS and HTTP. Each event is one READ COMMITTED transaction: idempotency-ledger insert, versioned listing upsert,
outbox row. PostgreSQL runs with its default configuration (`synchronous_commit = on`) on the same 2 vCPUs.

Each row is one run of 20,000 events (the first 20,000 valid records of the 100k export; "mixed" takes 10,000
from `northwind-supply` and 10,000 from `acme-market`). CPU and memory were sampled for the later runs with
`scripts/bench/cpu_sampler.py` (`wait4` rusage for the JVM, summed `utime + stime` of every `postgres` process):

```bash
python3 scripts/bench/cpu_sampler.py java -cp <test and runtime classpath> \
  io.catalogsentinel.core.benchmark.IngestionBenchmark \
  build/bench-100k/northwind-supply/listings-00001.jsonl:northwind-supply,build/bench-100k/acme-market/listings-00001.jsonl:acme-market \
  10000 8 [listingsToMatch]
```

| run | threads | throughput | p50 | p95 | p99 | host CPU busy | JVM CPU | PostgreSQL CPU | JVM peak RSS |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | 308 events/s | 3.1 ms | 5.7 ms | 8.8 ms | not sampled | | | |
| 2 | 1 | 460 events/s | 1.9 ms | 3.6 ms | 6.8 ms | 55 % | 0.62 cores | 0.42 cores | 243 MiB |
| 3 | 1 | 509 events/s | 1.8 ms | 3.2 ms | 5.6 ms | 56 % | 0.62 cores | 0.43 cores | 239 MiB |
| 4 | 8 | 898 events/s | 7.8 ms | 17.2 ms | 29.9 ms | not sampled | | | |
| 5 | 8 | 867 events/s | 8.0 ms | 18.5 ms | 28.4 ms | 98 % | 1.14 cores | 0.77 cores | 299 MiB |
| 6 | 8 | 828 events/s | 8.4 ms | 18.9 ms | 29.5 ms | 98 % | 1.11 cores | 0.78 cores | 302 MiB |
| 7 (mixed) | 8 | 536 events/s | 13.6 ms | 27.5 ms | 37.7 ms | not sampled | | | |
| 8 (mixed) | 8 | 826 events/s | 8.6 ms | 19.0 ms | 29.2 ms | 99 % | 1.13 cores | 0.79 cores | 287 MiB |
| 9 (mixed, before matching run 2) | 8 | 790 events/s | 8.7 ms | 19.8 ms | 32.8 ms | not sampled separately | | | |
| 10 (mixed, before matching run 3) | 8 | 830 events/s | 8.7 ms | 18.0 ms | 28.8 ms | not sampled separately | | | |
| 11 (mixed, before the profiled run; `pg_stat_statements` loaded) | 8 | 828 events/s | 8.5 ms | 18.8 ms | 28.7 ms | not sampled separately | | | |
| 12 (mixed, fresh database; `pg_stat_statements` loaded) | 8 | 699 events/s | 9.8 ms | 23.9 ms | 34.0 ms | 99 % | 0.99 cores | 0.92 cores | 245 MiB |

Every run ended with exactly 20,000 listings, 20,000 ledger rows and 20,000 outbox rows. Replaying 2,000 of the
same events afterwards produced 2,000 duplicates and no writes, at 3,200–8,400 events/s (the ledger lookup
short-circuits the transaction).

What the numbers say:

- **Run-to-run spread is large on this host.** The same single-thread run measured 308, 460 and 509 events/s.
  Nine eight-thread runs had a median of 828 events/s (range 536–898) and a median p99 of 29.5 ms (range
  28.4–37.7 ms). Quote the median and the range, not a single row.
- **One thread is latency-bound, eight threads are CPU-bound.** With one thread the host is about 55 % busy;
  with eight both vCPUs are saturated, shared between the JVM (1.0–1.1 cores) and PostgreSQL (0.8–0.9
  cores). That is why eight threads give only about 1.8 times the single-thread throughput (comparing medians),
  and why the p50
  rises from ~2 ms to ~8 ms: transactions queue for CPU. More cores, or the database on its own host, is the
  next thing to try. CPU figures cover each whole process (input parsing, the timed run and the replay), so
  they do not separate the engine from the benchmark driver.
- The JVM's resident memory stayed at or below about 300 MiB with default heap settings.

### DB-backed matching

`matchPending` (sequential, one transaction per listing) over the same 20,000 two-source listings, three full
runs:

| run | `random_page_cost` | wall time | throughput | candidate pairs | canonical products |
|---|---|---|---|---|---|
| 1 | 4 (default) | 616.0 s | 32 listings/s | 211,866 | 13,322 |
| 2 | 1.1 | 645.9 s | 31 listings/s | 211,864 | 13,326 |
| 3 | 4 (default) | 637.6 s | 31 listings/s | 211,871 | 13,326 |

Counts differ slightly because eight-thread ingestion assigns listing ids in a different order each run, which
changes the order in which identities form. During runs 2 and 3 PostgreSQL averaged 0.85 cores and the JVM
0.15 cores: matching is database-bound.

Where the database time goes, from `pg_stat_statements` (`scripts/bench/pg-stat-statements.sql`) over a run that
ingested the 20,000 events and then matched 3,000 of them against the full table (128.9 s of matching,
23 listings/s):

| statement | calls | mean | total | share of all statement time |
|---|---|---|---|---|
| Title-trigram candidate query | 3,000 | 32.4 ms | 97.1 s | 83.8 % |
| `INSERT INTO match_candidates …` | 60,986 | 0.11 ms | 6.7 s | 5.8 % |
| Listing, ledger and outbox inserts (ingestion) | 60,000 | 0.09–0.23 ms | 9.4 s | 8.1 % |
| Brand + model candidate query | 3,000 | 0.04 ms | 0.1 s | 0.1 % |
| GTIN candidate query | 2,714 | 0.02 ms | 0.05 s | 0.0 % |

Title-trigram retrieval is the matching bottleneck: about three quarters of the matching wall time, and a
thousand times the cost of the exact-identifier lookups. On this synthetic catalog many titles share category
nouns, so hundreds of rows clear pg_trgm's default 0.3 similarity threshold and must be rechecked and sorted.

### Trigram retrieval: GIN index versus sequential scan

The title-similarity candidate query (`MatchingRepository`: `normalized_title % :title … ORDER BY sim DESC
LIMIT :limit`) for one listing on the same 20,000 listings, three runs each, measured with
`EXPLAIN (ANALYZE, BUFFERS)`; the index was dropped inside a rolled-back transaction for the comparison:

| plan | execution time |
|---|---|
| Bitmap index scan on `ix_product_listings_title_trgm_active` (GIN, `gin_trgm_ops`) | 16.9–27.7 ms |
| Sequential scan | 207.6–219.6 ms |

The index makes this query roughly 8–12 times faster. That listing's query still rechecked 1,246 rows.

### Planner cost setting

With literal values in the query (custom plans) and PostgreSQL's default `random_page_cost = 4`, the planner
abandoned the GIN index for broad titles. Over 300 randomly sampled listings (`ORDER BY random()` after
`setseed(0.42)`), each query run under both settings (`scripts/bench/trigram-plan-sample.sql`):

| `random_page_cost` | sequential-scan plans | mean | p50 | p95 |
|---|---|---|---|---|
| 4 | 62 of 300 | 56.6 ms | 22.6 ms | 218.6 ms |
| 1.1 | 0 of 300 | 23.5 ms | 22.0 ms | 43.5 ms |

A second sample with the committed script, on a fresh load of the same data (listing ids differ because ingestion
ran on eight threads, so the sample contains other listings): 77 of 300 sequential plans at 4, none at 1.1; mean
63.2 ms against 23.5 ms, p95 210.3 ms against 36.6 ms.

**End-to-end matching did not change** (runs 2 and 3 above: 645.9 s against 637.6 s). The application sends the
query as a prepared statement, and after five executions PostgreSQL switches to a generic plan: with the title
unknown it estimates 200 matching rows and chooses the GIN index for every title. Reproduced with
`PREPARE`/`EXECUTE` in psql (`scripts/bench/generic-plan.sql`): after five custom plans, the sixth execution used
the generic plan (conditions on `$1`, an estimate of 200 rows) and served a broad title through the index in
45 ms, where custom plans for broad titles at the default cost took about 200 ms as sequential scans. Both runs of
this check gave the same result.

`random_page_cost = 1.1` is kept in the compose stack and the RDS parameter group because it is the right cost
model when the working set is cached or on SSD storage, and it protects queries planned with literal values
(ad-hoc SQL, and the first executions on each new connection). It is not a matching speed-up, and no document
here claims one.

The next optimisation to try is reducing the rows each trigram query rechecks: a higher
`pg_trgm.similarity_threshold`, `word_similarity` for long titles, or a category predicate. Each must keep
blocking recall at 100 % on the labeled sets (`MatchingServiceIT`, `GoldenSetEvaluationTest`) before it is
adopted.

## Re-measured after the repair pass (2026-11-03, `scripts/benchmark.sh`)

The repair pass changed matching (evidence currency, regrouping, ADR 0013), gap detection (eligibility, gaps
following merges, ADR 0014) and reconciliation execution (durable jobs, V6). `scripts/benchmark.sh` now records an
environment profile and runs every section the machine allows; this run's report, query plans and raw section
output are in `docs/evidence/repair-2026-11/15-*` (the corrected environment profile is `15b`). Same machine as
above, PostgreSQL 16.15 with its default settings (`shared_buffers` 128MB, `random_page_cost` 4,
`synchronous_commit` on), classes built by the javac harness because Maven Central is blocked.

### What stayed the same

| measurement | this run | earlier runs |
|---|---|---|
| Ingestion, 20,000 events (two sources), 8 threads | 756, 783, 852 events/s (p99 33.3, 31.7, 30.1 ms); a fourth ingest before the matching run: 790 events/s | median 828 events/s, range 536–898, median p99 29.5 ms |
| Redelivery of 2,000 ingested events | 2,000 of 2,000 recognised as duplicates in each of the four runs (the replay path returns before any write), 4,856–6,282 events/s | 3,200–8,400 events/s |
| Matcher quality, `rules-v4` (EvaluationCli) | golden dev auto P 1.000 R 0.456, assisted F1 0.829; holdout auto P 1.000 R 0.440, assisted F1 0.898; synthetic 10k labeled sample (seed 7) auto P 0.976 R 0.882, assisted F1 0.915; blocking recall 1.0 on all three | identical (the matcher did not change) |
| DB-backed matching quality (`MatchingServiceIT`) and gap quality (`GapReconciliationIT`) | retrieval recall 1.0, cluster P 1.0 R 0.456; 54 open gaps, confident P 0.778 R 0.737 | identical |
| Batch dry run, 42,992 records | parse/validate/normalize/dedup 18.3 s, write 5.3 s | 16.6 s and 3.7 s |

Every ingestion run ended with 20,000 listings, 20,000 ledger rows and 20,000 outbox rows. The ingestion path was
not changed by the repair; the spread is this host's noise.

### Matcher speed in memory

`EvaluationCli` times the rule-based matcher over all pairs of each labeled set on one thread: 163,878 pairs of the
573-listing synthetic sample in 1,485 ms (about 110,000 comparisons/s); 11,175 golden pairs in 237 ms (about 47,000/s,
before the JIT has warmed up). With blocking the same matcher needs 26 ms for the 3,160 candidate pairs of the
synthetic sample.

### Matching throughput before and after the repair

Same procedure as the `match` section, run alternately on a build of the pre-repair commit `e63e4b7` (from a
worktree, on its own V1–V4 schema) and of the repaired code: ingest 10,000 + 10,000 events on 8 threads into an
emptied database, then `matchPending` over 3,000 listings (`16-matching-ab-before-after.log`):

| run | build | ingestion | matching 3,000 listings | candidate pairs | canonical products |
|---|---|---|---|---|---|
| 1 | before (`e63e4b7`) | 806 events/s | 137.6 s (22 listings/s) | 54,797 | 2,996 |
| 1 | after | 538 events/s | 146.9 s (20 listings/s) | 54,797 | 2,996 |
| 2 | before | 840 events/s | 147.1 s (20 listings/s) | 54,796 | 2,996 |
| 2 | after | 776 events/s | 117.6 s (26 listings/s) | 54,838 | 2,996 |
| — | after (`match` section of the benchmark run) | 790 events/s | 124.6 s (24 listings/s) | 54,797 | 2,996 |

No slowdown is measurable: the repaired code took 117.6–146.9 s and the old code 137.6–147.1 s, and the same
host's ingestion varied from 538 to 840 events/s between consecutive runs, so the difference is within this
machine's noise. For listings seen for the first time the extra statements are cheap (one bitmap scan to supersede,
currency checks by index lookups); the trigram retrieval still dominates. Results are the same: every run produced
2,996 canonical products, and candidate counts differ by at most 42 of about 54,800 because eight-thread ingestion
assigns listing ids in a different order each run. The new paths (supersede, regroup, split) cost more only when
listings change, which this workload does not exercise; `StaleMatchEvidenceIT` covers their correctness, not
their speed.

### Event-to-link latency (in process) and one reconciliation

5,000 listings (2,500 per source) ingested on 8 threads and all matched (87.4 s, 57 listings/s; matching is faster
than above because the trigram queries search a quarter of the rows). Then 500 further events were each applied
and matched on the calling thread, as the worker's two consumers do, and one reconciliation of `acme-market`
against every reference source was run:

| measurement | result |
|---|---|
| Event to canonical link, one at a time (ingest transaction + `matchListing`) | p50 17.9 ms, p95 26.8 ms, p99 30.0 ms, max 34.0 ms; 55 events/s |
| Reconciliation, 2,734 reference products evaluated | 1.7 s: 1,701 carried, 1,033 gaps opened, 18 duplicate identities merged first |

Event-to-link excludes SQS, the outbox relay's polling and the consumers' long polling, which add the delivery
delays of the deployed path; only the `http` section of the benchmark (stack required) measures those.

### Query plans for the statements the repair added

`EXPLAIN (ANALYZE, BUFFERS)` on that database (5,500 listings, 43,894 candidates, 3,665 products, 1,033 gaps), with
`scripts/bench/repair-plans.sql`; writes happen in rolled-back transactions:

| statement | execution | plan |
|---|---|---|
| `linkableFor`, the listing with the most candidates (59 pairs, none linkable) | 0.26 ms | BitmapOr of the pair unique index (left side) and `ix_match_candidates_right`; the linkable filter removes all 59, so the currency lookups of both listings (`ix_product_listings_status_id`) never execute |
| `supersedeStaleFor` after that listing changed | 2.9 ms | Same bitmap scan, primary-key lookups of both listings, `LockRows` and the update of all 59 now-stale candidates in one statement |
| `findMergeablePairs` (merge pass) | **77.5 ms** | Starts from every active listing (sequential scans of listings and links, hash join), probes the pair index once per listing (5,500 loops) and keeps the 1,934 linkable pairs; nothing to merge |
| `referenceProducts`, one keyset batch of 200 | 1.1 ms | Index-only scan of `ix_listing_product_links_canonical` merged with the product primary key; eligibility evaluated per row |
| `bestCandidate` for one product | 0.15 ms | Index lookups only |
| `invalidatedGaps` | 5.6 ms | Index scan of `uq_selection_gaps_active` (1,033 live gaps), one lateral index lookup per gap |
| `liveGapsOnMergedProducts`, 500 products merged away | 2.7 ms | Recursive CTE over primary-key lookups |
| Job claim among 20,000 finished jobs | 0.32 ms | The partial unique index on active jobs (`uq_reconciliation_jobs_active_target`) finds the one due job; the V6 partial indexes are not needed at this size |

**Bottleneck found: merge discovery scales with the catalog, not with the change.** `findMergeablePairs` reads every
active listing to find linkable pairs, so its cost grows linearly with the catalog: 77 ms at 5,500 listings
suggests over a second at 100,000. It runs once before each reconciliation, which is acceptable, but also after
every match approval (`ReviewService` calls `mergeDuplicateIdentities(100)`), where it is pure overhead: the
approval has already linked both listings. Next steps, in order: scope the post-approval merge to the two products
involved, and give the global pass a partial index on linkable candidates (`review_status IN ('APPROVED',
'AUTO_ACCEPTED')`), which here would start from 1,934 rows instead of 43,894. Neither is done yet.

## Not measured in this environment

The Spring Boot services could not be built here (Maven Central is blocked) and no container image could be pulled
(Docker Hub is blocked), so these have no figures: HTTP latency percentiles, queue age and depth under load,
event-to-catalog latency through SQS and the worker, and recovery times after a worker crash or database outage.
`scripts/benchmark.sh` measures the first three in its `http` section (`scripts/e2e/http_bench.py`: 500 requests per
endpoint from 8 workers after a warm-up, p50/p95/p99/max; 200 queued listings polled until each is linked; queue
depths and the outbox's oldest pending age sampled every second) as soon as the stack runs, and the drills
(`scripts/inject-failure.sh`) check recovery. Until then the recovery bounds are the configured ones, not
measurements: a crashed reconciliation is resumed when its 5-minute lease expires; a failed attempt is retried after
30 s, then 60 s (at most 3 attempts); an unacknowledged message reappears after its 60 s visibility timeout.
