# Testing

What is tested, how to run it, and what was actually executed in the environment that built this project. That
environment had no access to Maven Central or Docker Hub, so part of the suite was compiled and run with a
substitute harness and part could only be type-checked; both are stated per suite below.

## How to run

```bash
mvn -B verify                                   # all Java modules: unit tests, PostgreSQL ITs (Testcontainers)
(cd jobs/catalog-batch && pip install -r requirements-lock.txt && pip install --no-deps -e . && pytest -q)
(cd apps/dashboard && npm ci && npm test)       # Vitest + Testing Library + MSW
python -m pytest scripts/e2e                    # smoke and seed tooling logic (no stack needed)
docker compose up -d --build --wait && ./scripts/smoke.sh
./scripts/inject-failure.sh db-outage           # also worker-crash, poison-message, batch-crash
```

CI (`.github/workflows/ci.yml`) runs the first three on every push and pull request, then builds the images and
runs the smoke test against the compose stack.

## Suites

### Java: domain rules (`libs/catalog-domain`, plain JUnit)

| Test class | Covers |
|---|---|
| `ListingEventDeciderTest` | The full decision table: create, update, unchanged, stale, same-version conflict, tombstones, resurrection attempts, reactivation, out-of-order convergence |
| `ListingContentAndHashingTest` | Content hashing (order and whitespace insensitive, injective across field boundaries), GTIN validation and canonicalisation, event validation reporting every violation |
| `NormalizationTest`, `QuantityVariantConflictTest` | Units, model generations, regional suffixes, pack counts, colour families, voltage and ounce variants |
| `SimilarityTest` | Jaro-Winkler reference values, trigrams padded like pg_trgm, Jaccard and overlap |
| `RuleBasedMatcherTest` | Explicit rules: equal GTINs, generation and storage variants, tier words, brand ownership, symmetry over the whole golden set, bounded feature scores, deterministic evidence order |
| `GoldenSetEvaluationTest` | Quality floors on the golden development set: blocking recall = 1.0 with more than 90 % pruning; auto-match precision ≥ 0.99, auto-match recall ≥ 0.40, assisted F1 ≥ 0.80, and assisted F1 at least 0.30 above the title-only baseline |
| `GapRulesTest` | Gap lifecycle transitions; confidence and priority ordering by reason |
| `NormalizationParityFixtureTest` | The Java normalizer reproduces every row of `tests/fixtures/normalization-parity-golden.tsv`, which the Python batch job must also match |

### Java: application services against PostgreSQL (`libs/catalog-core`)

| Test class | Covers |
|---|---|
| `ListingIngestionServiceIT` | Every ingestion rule end to end: idempotent replay, reused ids, version ordering, same-version conflicts, tombstones, out-of-order convergence, unknown or disabled sources, outbox rows only for real changes, listing history |
| `ConcurrentIngestionIT` | 10 rounds of 8 simultaneous deliveries of one event (one effect per round; prints how many deliveries were settled by the ledger's primary key), concurrent creation of one listing, shuffled concurrent versions, independent listings in parallel |
| `AsyncPipelineIT` | Queue consumer + ingestion + outbox + matching with an in-memory queue: duplicate and out-of-order deliveries, crash before acknowledging, database outage (retried, not dead-lettered), poison messages and replay, outbox relay driving matching, relay failures and requeue, concurrent relays never publishing a row twice |
| `QueueConsumerTest` | Acknowledgement, jittered backoff, bounded retries, immediate dead-lettering of poison messages, failure classification, redrive as the crash safety net, concurrency limit and draining on stop, visibility heartbeat for slow handlers, outage pause and resume |
| `AsyncIngestionServiceTest`, `ListingEventMapperTest` | Batch validation before anything is queued; stamped metadata and attributes; contract-to-domain mapping |
| `MatchingServiceIT`, `TrigramParityIT` | SQL candidate retrieval finds every true pair of the golden set; precise clusters; idempotent re-matching; review queue; Java trigram similarity equals `pg_trgm` |
| `GapReconciliationIT` | Gaps against the golden catalog's ground truth, idempotent reconciliation, audited lifecycle, append-only audit trail (UPDATE, DELETE, TRUNCATE rejected by triggers), dismissal suppressing re-detection, automatic resolution, one active run per target, abandoned-run recovery, dashboard summary |
| `StaleMatchEvidenceIT` | Match evidence currency (ADR 0013): a changed listing leaves the shared product and stale pairs cannot rejoin it; a changed representative does not rewrite the product of unchanged members; withdrawal supersedes evidence and reactivation rebuilds it; a human approval is superseded with an audit row; redelivered events change nothing; a product held together only by a changed listing splits |
| `ReferenceSourceEligibilityIT` | One eligibility rule for gap detection: all enabled sources count; disabling a source resolves its exclusive gaps (`REFERENCE_SOURCE_DISABLED`) and recomputes coverage and demand, re-enabling reopens them; scoped runs; a disabled source cannot be selected; a source disabled after the request fails the job visibly |
| `ReconciliationJobDurabilityIT` | Durable jobs: requested while no runner is up then run; crash with lease expiry; fencing of a runner that lost its lease; retry with backoff and the error visible; bounded attempts; crash on the last attempt; saturation (jobs wait in the database); two pollers run each attempt once |
| `GapsFollowProductMergesIT` | Gaps follow merges (ADR 0014): resolved as carried when merged into a carried product; a dismissal keeps suppressing; a confirmation stays; a conflict keeps the survivor's gap and records why the other was resolved |
| `SmokeScenarioIT` | The smoke test's scenario (`scripts/e2e/smoke-scenario.json`) in process for three run tokens and both change-event orders, so the smoke test's expected outcomes are checked against the real code |
| `CoverageIT` | Coverage aggregation adds up and matches the gap table |
| `IngestionJobServiceIT` | Idempotent job registration, key reuse conflicts, lifecycle, restart as attempt + 1, progress derived from the ledger, pagination |
| `SourceServiceIT`, `ListingQueryIT` | Source registration and conflicts; filtering and keyset pagination without gaps or duplicates |

### Java: applications (`apps/*`)

| Test class | Covers | Status here |
|---|---|---|
| `ApiKeyAuthenticationFilterTest` | Hashed key parsing, roles, plaintext rejection | Executed: 2/2 (pure Java; the servlet superclass came from a stub) |
| `CatalogApiIT` | HTTP contract with MockMvc and a PostgreSQL container: status codes per outcome, validation errors, async endpoint, pagination, health and OpenAPI | Type-checked only |
| `SecuredApiIT` | Authorization matrix of the `aws` profile | Type-checked only |
| `WorkerPipelineIT` | Worker with PostgreSQL and ElasticMQ containers: listing event through ingestion, outbox and matching; duplicate deliveries; poison message; unknown source recovered by replay | Type-checked only |

### Batch job (`jobs/catalog-batch`, pytest with a local Spark session and a moto server)

| File | Tests | Covers |
|---|---|---|
| `test_normalize_parity.py` | 853 | The Python port of the normalizer against the shared parity fixture (parameterised cases) |
| `test_validation.py` | 15 | Record validation identical to the Java rules, CSV and JSON Lines mapping |
| `test_manifest_and_events.py` | 10 | Manifest schema, checksum verification, job key derivation, deterministic event ids and partitioning |
| `test_pipeline.py` | 7 | Full run (validate, dedup, outputs, publish), re-running a published manifest is a no-op, crash during publishing resumed without losing events, S3 staged mode, CSV exports, tampered input, malformed manifest |
| `test_synthetic_dataset.py` | 4 | The synthetic generator's output feeds the pipeline as documented |

### Dashboard (`apps/dashboard`, Vitest + Testing Library + MSW)

17 tests over contract-shaped fixtures (`test/fixtures/api.json`) served by MSW at the network layer:
coverage overview and planogram facing allocation, attention figures linking to filtered lists, empty and error
states (with the correlation id to search for), catalog search and listing history, gap triage (synthetic demand
labelled, reason required to dismiss, idempotency key sent), match review with evidence, batch job progress,
reconciliation errors, throughput with a table view, dead letters and the admin-role explanation.

### Smoke test

`scripts/smoke.sh` runs `scripts/e2e/smoke.py` (standard-library Python) against a running stack. Each run uses a
fresh token, so it registers its own reference, marketplace and batch sources, with a run-specific brand, category
root and GTINs; runs never interfere with each other or with seeded demo data, and the run's sources are disabled
at the end (`--keep` leaves them). The fixtures and expected outcomes are in `scripts/e2e/smoke-scenario.json`.

| Section | What it checks exactly |
|---|---|
| Ingestion semantics | Create (201, `Location`), exact replay (200, `Idempotent-Replay: true`, `duplicate`), reused event id (409 `EVENT_ID_REUSED`), older version (`STALE_IGNORED`), same version with other content (409 `VERSION_CONFLICT`), withdrawal, a stale upsert after it, reactivation; final state and the listing's ledger (exactly six recorded outcomes) |
| Queue and redelivery | 202 for the queued events and for a second delivery of one of them; 400 `VALIDATION_FAILED` naming `events[0].payload.gtin` for an invalid event; after the queues drain, one listing and one ledger entry for the redelivered event, and the worker's `duplicate="true"` counter went up |
| Matching | Every listing linked; exactly one `PENDING` review candidate for the review-band listing, paired with the right reference listing |
| Reconciliation 1 | `202` with `Location`, same job for the same `Idempotency-Key`, `SUCCEEDED`; the reference and marketplace kettle converge on one canonical product that lists both; exactly two live gaps (`NO_CANDIDATE` for the lantern, `UNCONFIRMED_CANDIDATE` naming the review-band listing for the drive); coverage row for the run's category: 3 reference products, 1 carried, 2 open |
| Decisions | Gap `CONFIRM` and match `APPROVE`, each with an `Idempotency-Key` that returns the same decision id on replay; the gap's audit trail and the candidate's reviewer and time; approval links both listings |
| Reconciliation 2 | Lantern gap `CONFIRMED`, drive gap `RESOLVED` as `AUTO_MATCH_FOUND`; coverage 2 carried, 1 confirmed, 0 open; filtered gap and listing queries return exactly the fixtures; summary and processing metrics respond |
| Batch | A four-record export (one invalid GTIN) staged in the S3 emulator and run through the batch container: `PUBLISHED` with 4 rows, 1 rejected, 3 events; the job becomes `fullyProcessed` with 3 `CREATED`; 3 listings linked; running the same manifest again is skipped and processes nothing |
| Dead letters | Fails as soon as either dead-letter queue grows (with the messages' failure reasons), and at the end if any outbox row failed |

Every wait has a deadline (`SMOKE_WAIT_SECONDS`, default 180) and the run a hard limit (`SMOKE_MAX_SECONDS`, 1200);
a timeout prints the last observed state and diagnostics (queue depths, outbox, dead letters, recent
reconciliation jobs, the run's listings) and exits 1. `--batch skip` (`SMOKE_BATCH=skip`) skips the batch section
explicitly for a deployed stack without compose; skipped sections are listed in the result line. Exit codes: 0
passed, 1 failed, 2 usage or environment error.

The expectations are verified without a stack in three places: `SmokeScenarioIT` runs the same scenario against
PostgreSQL with the services the API and worker use (three run tokens, both change-event orders);
`jobs/catalog-batch/tests/test_smoke_export.py` runs the smoke export through the real PySpark pipeline; and
`scripts/e2e/test_smoke.py` covers the script's own logic (fixture expansion identical to the Java side, polling
deadlines, HTTP failures, dead-letter detection, exit codes).

### Demo data

`scripts/seed-demo.sh [10k|100k]` (`scripts/e2e/seed_demo.py`) generates the synthetic exports, registers the
demo sources, stages and runs the batch job per source, waits until every job is `fullyProcessed` and the queues
have drained, then runs one reconciliation for `acme-market` and waits for it. It is deterministic and idempotent:
the exports are byte-identical, so job keys, event ids and the reconciliation's `Idempotency-Key` repeat and a
second run changes nothing. Each step has a deadline within `SEED_MAX_SECONDS` (1800 s for 10k, 7200 s for 100k);
a failure or timeout prints the failed step, job progress, queue depths, dead letters, `docker compose ps` and
recent service logs, and exits 1. Unit tests: `scripts/e2e/test_seed_demo.py`.

### Failure drills

`scripts/inject-failure.sh` (`scripts/e2e/drills.py`) provokes a database outage, a killed worker, a poison message
and a crashed batch job, and checks the documented outcome of each with deadlines, exit codes and diagnostics
([runbook](runbook.md#failure-drills)). Unit tests: `scripts/e2e/test_drills.py`; the batch-crash drill's export
is crashed and resumed through the real pipeline in `jobs/catalog-batch/tests/test_smoke_export.py`.

## Results in this environment (2026-11-03)

Every run below is recorded with its command, time and output in
[REPAIR_VERIFICATION_LOG.md](REPAIR_VERIFICATION_LOG.md) and `docs/evidence/repair-2026-11/`.

| Suite | Result | How it was run |
|---|---|---|
| Java domain + contracts + core | **242 tests, 242 passed** | `javac` against Ubuntu-packaged Spring 4.3 JDBC jars (only API that is unchanged in Spring 7 is used) and JUnit 5 on the console launcher, against a real PostgreSQL 16.15 with the Flyway migration files applied by `psql`; two test-support classes substituted (external database instead of Testcontainers, Jackson 2 instead of Jackson 3). **Not a Maven build** |
| Red runs before each fix | `StaleMatchEvidenceIT` 5 of 6 failed; `ReferenceSourceEligibilityIT` 4 of 5; `GapsFollowProductMergesIT` 4 of 4 | Same harness; the first two re-created against worktrees of the pre-fix commits |
| `HealthDetailsPolicyTest`, `ApiKeyAuthenticationFilterTest` | 3 passed | Console launcher (the second against Spring Security API stubs) |
| API and worker main code, `CatalogApiIT`, `SecuredApiIT`, `WorkerPipelineIT` | Compile with `-Werror`, no warnings | Against hand-written stubs of the Spring Boot 4 / Security 7 / Micrometer / AWS SDK v2 / Jackson 3 / Testcontainers APIs. Proves internal consistency only |
| `mvn -B -ntp verify` | **Not run: blocked** | The parent POM cannot be resolved; the egress proxy refuses repo.maven.apache.org (403) |
| Batch job | **891 tests, 891 passed** | pytest with PySpark 4.2.1 (`local[2]`) and moto 5.2.5 in server mode |
| Smoke, seed, drill and HTTP-benchmark tooling | **33 tests, 33 passed** | pytest (`scripts/e2e`); no stack needed |
| Dashboard | **17 tests, 17 passed**; `npm ci` and the production build succeed | Vitest 3.2, jsdom |
| Terraform | 22 files parse; checkov 247 passed, 0 failed (16 justified skips) | python-hcl2, checkov 3.4.3; `terraform validate` not run (no binary, registry unreachable) |
| Compose, workflows, scripts | `docker compose config` valid for every profile; actionlint, shellcheck, pyflakes clean | Docker Compose 5.6.1 |
| Compose stack, images, smoke test, seed, failure drills | **Not executed** | The Docker daemon starts but every Docker Hub pull is refused (403); against the absent stack each script fails within its deadline with exit 1 and diagnostics |

The first thing to run on a machine with network access is `mvn -B verify`: it compiles the Spring Boot
applications against the real libraries and runs every Testcontainers test, including the three application
ITs above. Then the stack: `docker compose up -d --build --wait && ./scripts/seed-demo.sh 10k && ./scripts/smoke.sh`.

## Measured quality

Matching accuracy for every matcher version, on hand-labeled and synthetic sets, with the bias of each set:
[benchmarks/matching/README.md](benchmarks/matching/README.md). Throughput, latency, CPU, memory and query
measurements: [benchmarking.md](benchmarking.md).
