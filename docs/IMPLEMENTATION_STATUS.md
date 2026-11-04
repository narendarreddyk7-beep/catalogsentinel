# Implementation status

> Living checkpoint for humans and agents. Last update: 2026-11-03, after the repair pass on branch
> `repair/verification-2026-11` (stale match evidence, reference-source eligibility, durable reconciliation jobs,
> gaps following merges, smoke/seed/drill tooling, P2 hardening). Every verification step of that pass, with
> command, time, result and evidence file, is in [REPAIR_VERIFICATION_LOG.md](REPAIR_VERIFICATION_LOG.md). The open
> items are verification steps that need network access to Maven Central and Docker Hub, listed under
> [Next steps](#next-steps-on-a-networked-machine).

## Build environment caveat (read first)

The repository was built in a sandbox whose network policy **blocked Maven Central, Docker Hub, the GitHub and
Quay container registries and the HashiCorp registry** (npm, PyPI and the Ubuntu archive were reachable).
Consequences:

| Area | Verified here? | How |
|---|---|---|
| `libs/catalog-domain`, `libs/event-contracts` | **Yes** | `javac -Werror` + JUnit 5 (Ubuntu packages) |
| `libs/catalog-core`, including all SQL | **Yes** | `javac -Werror` against Spring JDBC/TX 4.3 jars (only API unchanged through Spring 7 is used) + JUnit against a **real PostgreSQL 16.15**, migrations applied with `psql` one transaction per file |
| `libs/platform-adapters`, `apps/catalog-api`, `apps/ingestion-worker` and their tests | **Type-checked only** | `javac -Werror` against the real core classes plus hand-written stubs of the Spring Boot 4 / Spring 7 / Security 7 / Micrometer / AWS SDK v2 / Jackson 3 / Testcontainers APIs. Proves internal consistency, not compatibility with the real libraries. `HealthDetailsPolicyTest` (pure Java) and `ApiKeyAuthenticationFilterTest` (against the stubs) were also executed |
| Testcontainers paths (`CatalogApiIT`, `SecuredApiIT`, `WorkerPipelineIT`, `TestDatabase`) | **No** | Need Docker Hub images |
| `jobs/catalog-batch` | **Yes** | pytest with a real Spark 4.2.1 session (`local[2]`) and moto 5.2.5 in server mode |
| `apps/dashboard` | **Yes** | Vitest + Testing Library + MSW; `tsc` + Vite production build; screenshots rendered with Playwright from fixtures |
| `infra/terraform` | **Static analysis only** | python-hcl2 parse, checkov; `terraform init/validate` needs the HashiCorp registry; never applied (by design) |
| Compose file | **Yes (static)** | `docker compose config` for every profile; every published port bound to 127.0.0.1 |
| Docker images, running stack, `smoke.sh`, `seed-demo.sh`, `inject-failure.sh` | **No** | The Docker daemon starts, but every image pull from Docker Hub is refused (`13-compose-up.log`). The scripts' own logic is unit-tested (`scripts/e2e`), their expected outcomes are pinned by `SmokeScenarioIT` and by the batch export tests, and run against the absent stack they fail fast with exit 1 (`14-…log`) |

The sandbox harness substitutes two test-support classes (`TestDatabase`: external database instead of
Testcontainers + Flyway; `TestJsonCodec`: Jackson 2 instead of Jackson 3). Product code is identical.

## Phase checklist

| Phase | Status | Notes |
|---|---|---|
| 0 Starter audit | Done | No starter archive existed in the workspace; the implementation was created from scratch |
| 1 Catalog core | Done | Domain rules, migrations, ingestion engine (ledger, version ordering, tombstones, outbox), sources and listings API |
| 2 Product intelligence | Done | `norm-v2`, blocking, explainable matcher `rules-v4`, canonical products, review queue, evaluation history v1–v4 |
| 3 Gap detection | Done | `gap-v1` reconciliation, confidence, priority with synthetic demand, lifecycle, append-only audit, idempotent jobs |
| 4 Distributed events | Done (core executed; worker app type-checked) | At-least-once consumer, outbox relay, DLQ replay, backlog gauges, readiness, ElasticMQ topology |
| 5 Batch processing | Done | Job lifecycle API; PySpark job; synthetic generator (10k/100k/1M); ESCI evaluated, not integrated (ADR 0009) |
| 6 Frontend | Done | Six views on the real API, 17 tests, validated palette, light and dark themes |
| 7 Production engineering | Done (static verification only for images, compose, Terraform, CI) | Images, compose with emulators and observability profile, alerts, CI, approval-gated OIDC deploy, Terraform, failure drills, benchmark harnesses |
| 8 Portfolio delivery | Done | README, architecture/database/API/security/runbook/deployment/testing docs, five diagrams, 12 ADRs, demo script, interview guide, resume bullets, this matrix |
| Repair pass (2026-11-03) | Done (core executed; stack steps blocked) | P0 match evidence currency (ADR 0013); P1 reference eligibility, durable reconciliation jobs (V6), gaps following merges (ADR 0014), real-dependency attempts recorded, smoke/seed/drills with exact checks and deadlines; P2 health details, local ports, plan-only deploys, pom link; benchmarks re-run. Log: [REPAIR_VERIFICATION_LOG.md](REPAIR_VERIFICATION_LOG.md) |

## Test outcomes (executed 2026-11-03)

| Suite | Result |
|---|---|
| Java: catalog-domain + event-contracts + catalog-core (sandbox harness, PostgreSQL 16.15) | **242 / 242 passed** (214 before the repair pass; +6 `StaleMatchEvidenceIT`, +5 `ReferenceSourceEligibilityIT`, +8 `ReconciliationJobDurabilityIT`, +5 `GapsFollowProductMergesIT`, +4 `SmokeScenarioIT`) |
| Java: `HealthDetailsPolicyTest`, `ApiKeyAuthenticationFilterTest` | 3 / 3 passed |
| Java: API and worker main sources, `CatalogApiIT`, `SecuredApiIT`, `WorkerPipelineIT` | compile with `-Werror` against stubs, no warnings; **not executed** |
| Python: jobs/catalog-batch | **891 / 891 passed** (853 are normalizer parity cases; +2 run the smoke and drill exports through the pipeline), 66 s |
| Python: smoke, seed, drill and HTTP-benchmark tooling (`scripts/e2e`) | **33 / 33 passed** |
| Dashboard | **17 / 17 passed**; production build OK (370 kB JS, 112 kB gzipped) |
| Terraform | 22 files parse; checkov **247 passed, 0 failed**, 16 skips each verified to fail without its skip; `terraform validate` not run (no binary, registry unreachable) |
| Workflows and scripts | actionlint (with shellcheck of every `run` step), shellcheck, pyflakes: clean |

## Feature verification matrix

Status values: **Executed** = covered by tests run in this environment; **Type-checked** = implemented, tests or
wiring compile but were not run; **Static** = checked by parsing or static analysis only; **Not run** =
implemented, not exercised here; **Partial** = part of the requirement is missing, stated in the notes.

### Data model and ingestion

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| Ten tables with constraints, foreign keys, indexes; additive Flyway migrations | `db/migrations` V1–V6 | All core ITs run on the migrated schema | Executed |
| ER diagram and database documentation | `docs/database.md`, `docs/diagrams/er-diagram.*` | — | Done |
| Versioned event contracts (create/update, withdrawal) | `libs/event-contracts` | Mapper and pipeline tests | Executed |
| Identical replay is idempotent; reused id with different data conflicts | Ledger + fingerprint | `ListingIngestionServiceIT`, `CatalogApiIT` | Executed (API IT type-checked) |
| Newer version updates; older ignored; same-version conflict rejected | `ListingEventDecider` | Decider table test, ingestion IT | Executed |
| Tombstones; no resurrection by stale events; newer event reactivates | Decider + listing row | Decider test, ingestion IT | Executed |
| Invalid data rejected with every violation | `ListingContent`, `ListingEventMapper` | Unit tests | Executed |
| Concurrent processing | Row locks, `ON CONFLICT`, ledger primary key | `ConcurrentIngestionIT` (10 × 8 simultaneous deliveries; 44 settled by the key in the recorded run) | Executed |
| Transactional outbox | `OutboxRepository`, `OutboxRelay` | `AsyncPipelineIT` (incl. concurrent relays) | Executed |

### Matching

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| Normalization preserving generations, capacities, colours, sizes, variants | `norm-v2` | `NormalizationTest`, `QuantityVariantConflictTest`, parity fixture | Executed |
| Indexed candidate retrieval (brand/model index, `pg_trgm`) | `CandidateBlocker`, `MatchingRepository` | `GoldenSetEvaluationTest` (recall 1.0), `MatchingServiceIT`, `TrigramParityIT` | Executed |
| Feature scores (brand, model, title, GTIN, attributes) with stored evidence | `RuleBasedMatcher`, `match_candidates` | `RuleBasedMatcherTest`, `MatchingServiceIT` | Executed |
| Auto-match / review / no-match thresholds; explicit identifier-conflict rules | `MatchThresholds`, matcher rules | Matcher tests | Executed |
| Match evidence stays current: changed or withdrawn listings leave products, stale pairs cannot rejoin them, stale human decisions are superseded with an audit row, stale candidates cannot be reviewed | Content-hash currency in linking, merge discovery and merges, gap evidence and review; regroup by current evidence ([ADR 0013](adr/0013-match-evidence-currency.md)) | `StaleMatchEvidenceIT` (5 of 6 red before the fix, re-run against the pre-fix commit: `01-…log`) | Executed |
| Labeled dataset with positives, negatives, variants, hard negatives | `tests/golden-matching` (dev + holdout), synthetic labeled samples | — | Done |
| Precision, recall, F1, false positives, retrieval recall; baseline vs optimized | `EvaluationCli`, `docs/benchmarks/matching` | Reports for title baseline and rules-v1…v4; floors in `GoldenSetEvaluationTest` | Executed |

### Gap detection

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| Canonical-aware detection, dedup, confidence, evidence, priority | `GapAssessor`, `ReconciliationService`, partial unique index | `GapRulesTest`, `GapReconciliationIT` (19/19 true gaps; confident precision 0.778, recall 0.737) | Executed |
| Lifecycle, resolved tracking, manual review and dismissal, audit | `GapLifecycle`, `ReviewService`, append-only `review_decisions` | `GapReconciliationIT` incl. trigger enforcement | Executed |
| Reference-source eligibility: disabled sources' exclusive products stop being gaps; one rule for coverage numerator, denominator, demand and dashboard; scoped runs; re-enable reopens | `GapRepository` eligibility predicate, `REFERENCE_SOURCE_DISABLED` | `ReferenceSourceEligibilityIT` (4 of 5 red before: `02-…log`) | Executed |
| Gap decisions survive product merges; outcome independent of event order | Gaps follow `merged_into_id` to the survivor ([ADR 0014](adr/0014-gaps-follow-product-merges.md)) | `GapsFollowProductMergesIT` (4 of 4 red before: `04-…log`), `SmokeScenarioIT` (both change orders) | Executed |
| Idempotent reconciliation; one active run per target; abandoned-run recovery | Partial unique index; durable jobs claimed by a database poller with leases, fencing and bounded retries (V6) | `GapReconciliationIT`, `ReconciliationJobDurabilityIT` | Executed (PostgreSQL 16, sandbox harness) |
| Synthetic demand only, labeled | Generator field, evidence `"source": "SYNTHETIC"`, dashboard label | Gap evidence assertions, dashboard test | Executed |
| Investigation endpoints | `GET /gaps`, `/gaps/{id}`, coverage | `CoverageIT` (query layer); controllers type-checked | Executed / Type-checked |

### Distributed processing

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| Separate Java worker with SQS (AWS SDK v2) | `apps/ingestion-worker`, `SqsQueueClient` | `WorkerPipelineIT` against ElasticMQ | Type-checked |
| Producers and consumers, concurrent processing, idempotent updates | `AsyncIngestionService`, `QueueConsumer`, ingestion service | `AsyncIngestionServiceTest`, `QueueConsumerTest`, `AsyncPipelineIT` (in-memory queue) | Executed |
| Visibility timeout handling | Backoff via `ChangeMessageVisibility`; heartbeat for slow handlers | `QueueConsumerTest` incl. heartbeat test | Executed |
| Bounded retries, exponential backoff with full jitter, DLQ, replay | `QueueConsumer`, `BackoffPolicy`, `DeadLetterService` | `QueueConsumerTest`, `AsyncPipelineIT`; admin endpoints type-checked | Executed |
| Graceful shutdown | Consumer drain; `SmartLifecycle` phases in the worker | Drain tested in `QueueConsumerTest`; lifecycle wiring type-checked | Executed / Type-checked |
| Queue backlog monitoring | `BacklogMonitor` gauges, `BacklogHealthIndicator`, `/metrics/processing` | Dashboard tests use the endpoint shape | Type-checked |
| Structured logs, correlation ids | ECS JSON profile, `CorrelationIdFilter`, consumer MDC | Correlation header asserted in `CatalogApiIT` | Type-checked |
| Locally runnable SQS-compatible setup | ElasticMQ in compose with matching queues and redrive | `docker compose config` in CI | Not run |
| Tests for duplicate delivery, worker failure, database outage, poison messages | In-memory pipeline tests; ElasticMQ IT; failure drills | `AsyncPipelineIT` executed; `WorkerPipelineIT` type-checked; drills not run | Executed / Not run |

### Batch processing

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| Input files and manifests; schema validation; checksums | `manifest.py`, `validation.py` | pytest | Executed |
| Raw data in S3-compatible storage; cleaned, partitioned output; invalid records separated | `storage.py` (staged and native modes), curated Parquet by `category_root`, rejected JSONL | pytest with moto S3 | Executed |
| Spark normalization with Java parity | `normalize.py` | 853 parity cases against the Java-generated fixture | Executed |
| Bulk catalog ingestion; restartable, idempotent jobs; processing metrics | Job API, deterministic event ids, partition markers, stage timings | `test_pipeline.py` (crash and resume), `IngestionJobServiceIT` | Executed |
| Bulk reconciliation after import | `seed-demo.sh` waits for every batch job to be `fullyProcessed`, then runs one reconciliation with an `Idempotency-Key` derived from the job keys and waits for it | Logic unit-tested; script not run against a stack | Not run |
| ESCI dataset: verify format, permissions, limitations | ADR 0009 | Licence and file listing could not be re-checked (network); not integrated | Partial |
| Synthetic generator for 10k / 100k / 1M with duplicates, gaps, misspellings, malformed and out-of-order records | `scripts/generate-synthetic-data.py` | `test_synthetic_dataset.py`; 1M generated in 23.8 s | Executed |

### API, dashboard, security, observability, deployment

| Requirement | Implementation | Verification | Status |
|---|---|---|---|
| 18 required endpoints (plus jobs, coverage, admin) | `apps/catalog-api` controllers | `CatalogApiIT`; services and queries executed in core ITs | Type-checked |
| Validation, consistent errors, status codes, filtering, pagination | Problem details, keyset cursors | Core ITs (pagination incl. inserts mid-paging); `CatalogApiIT` | Executed / Type-checked |
| OpenAPI specification and examples | springdoc annotations; `docs/api.md`; CI exports `/v3/api-docs.yaml` | Not generated here | Not run |
| Six dashboard pages on the real API, loading/empty/error states | `apps/dashboard` | 17 tests over contract-shaped fixtures; never run against a live API here | Executed (fixtures) |
| Authentication, roles, admin protections, HTTPS | API keys (hashed), `SecurityConfiguration`, ALB rules, TLS policy | `ApiKeyAuthenticationFilterTest` executed; `SecuredApiIT` type-checked; checkov | Executed / Type-checked / Static |
| Health details only for ADMIN when authentication is on; local mode documented and bound to 127.0.0.1 | `show-details: when-authorized`, `roles: ADMIN`; `HealthDetailsPolicy` refuses `always` with security on; compose `BIND_ADDRESS` | `HealthDetailsPolicyTest` executed; `SecuredApiIT.healthDetailsAreShownOnlyToAdminKeys` (anonymous, READER, REVIEWER, INGEST, ADMIN) type-checked; `docker compose config` | Executed / Type-checked / Static |
| Least-privilege IAM, environment configuration, secrets management | Per-task roles, batch policy, Secrets Manager, RDS-managed password | checkov | Static |
| Health, metrics, tracing, alerts | Actuator probes, Micrometer, OTel/OTLP, Prometheus rules, CloudWatch alarms | Configuration only | Not run |
| Terraform: ECS Fargate, RDS, SQS + DLQ, S3, CloudWatch, IAM, networking | `infra/terraform` (6 modules) | Parse + checkov; `terraform validate` in CI | Static |
| CI/CD | `ci.yml`, `deploy.yml` (manual, approval-gated, OIDC, arm64 images; a plan-only run builds and pushes nothing) | actionlint | Static |
| No paid deployment without approval | Deploy only by manual dispatch with environment approval; never auto-applied | — | Done |

### Benchmarks and deliverables

| Requirement | Status |
|---|---|
| Products and events processed, ingestion throughput, processing latency, database query performance, memory, CPU, matching accuracy, baseline vs optimized, machine specs, dataset sizes, commands, durations | Executed on the development machine ([benchmarking.md](benchmarking.md)) |
| API p50/p95/p99 latency, queue backlog under load, recovery time | **Not measured** (services cannot run here); `scripts/benchmark.sh` (section `http`) and the drills measure them against the compose stack. In-process event-to-link latency and reconciliation time were measured |
| README, architecture, ER and sequence diagrams, API docs, testing and benchmark reports, runbook, CI/CD, AWS docs, demo data and scripts, demo script, interview guide, resume bullets | Done |
| Definition of done: local demonstration (sources, ingestion, duplicates, out-of-order, matching, gaps, review, async, batch status, worker recovery, API, dashboard, tests) | Implemented and scripted (`seed-demo.sh`, `smoke.sh`, `inject-failure.sh`, [demo script](demo-script.md)); **not executed end to end here** |

## Measured results (reproducible from the repository)

- **Matching** ([benchmarks/matching/README.md](benchmarks/matching/README.md)): blocking keeps 100 % of true pairs
  while pruning 93–98 %. `rules-v4` golden dev: auto precision 1.000 / recall 0.456, assisted F1 0.829 (title
  baseline 0.352); holdout: auto 1.000 / 0.440, assisted F1 0.898 (optimistic); synthetic seed 2027: auto precision
  0.982 / recall 0.875, up from 0.920 / 0.875 for `rules-v3`.
- **Gap detection** (golden catalog): 19 of 19 true gaps surfaced; confident gaps precision 0.778, recall 0.737.
- **Ingestion engine** (2 vCPU, PostgreSQL on the same host): median 460 events/s on 1 thread (308–509, 3 runs);
  median 828 events/s on 8 threads (536–898, 9 runs), median p99 29.5 ms; 8 threads saturate both cores (JVM
  1.0–1.1, PostgreSQL 0.8–0.9); JVM RSS ≤ about 300 MiB. Replays: 2,000 of 2,000 recognised, no writes.
- **Matching throughput**: 31–32 listings/s single-threaded, database-bound; the title-trigram query is 84 % of
  statement time (32 ms mean). GIN index 8–12× faster than a sequential scan. `random_page_cost = 1.1` improves
  literal-valued queries 2.4× but not the workload (generic plans).
- **Batch**: 431,719-record export (dry run) in 83.9 s; 1M-listing dataset generated in 23.8 s.
- **After the repair pass** (`scripts/benchmark.sh`, [benchmarking.md](benchmarking.md#re-measured-after-the-repair-pass-2026-11-03-scriptsbenchmarksh)):
  ingestion unchanged (756–852 events/s, 8 threads); in-process event-to-link p50 17.9 ms, p99 30.0 ms (500
  events, excludes SQS); one reconciliation of 2,734 reference products in 1.7 s; every new statement under 6 ms
  except merge discovery (77.5 ms at 5,500 listings, linear in the catalog: the next optimisation). Matching throughput before and after the repair is the same within noise (117.6–146.9 s against 137.6–147.1 s for
  3,000 listings; identical products).

## Known issues and risks

- The Spring Boot 4.1 / Spring Security 7 / Jackson 3 / Testcontainers 2 / AWS SDK code paths have never been
  compiled against the real libraries. Likely failure points: Boot 4 package names
  (`org.springframework.boot.health.contributor`, `org.springframework.boot.webmvc.test.autoconfigure`), Spring
  Security 7 DSL details, OTLP property names, Micrometer/OpenTelemetry starter wiring.
- ElasticMQ queue URLs use `node-address.host = localhost`; the AWS SDK sends requests to its configured endpoint,
  so this should not matter, but it is unverified.
- The deploy workflow and Terraform have never run; the first deployment needs the ECR bootstrap step in
  [deployment.md](deployment.md).
- Matching throughput is limited by title-trigram retrieval; reducing rows per query must be gated on the recall
  floors.
- Reconciliation jobs are durable (database poller with leases), but the poller runs in the API process and shares
  its connection pool; heavy reconciliations compete with API requests for connections. Moving the poller to the
  worker or a dedicated task is a configuration change (`RECONCILIATION_POLLER_ENABLED`).
- The smoke test, seed and drills have never run against the stack; their expected outcomes are pinned by tests,
  but HTTP-level details (status codes, headers, field names) are checked only by reading the controllers.

## Next steps on a networked machine

1. `mvn -B verify`: compiles the Spring Boot apps against the real libraries and runs every test including the
   Testcontainers ITs. Fix any API drift (see risks above).
2. `docker compose up -d --build --wait && ./scripts/seed-demo.sh 10k && ./scripts/smoke.sh` (exit codes and
   diagnostics: [testing.md](testing.md#smoke-test)).
3. `./scripts/inject-failure.sh db-outage | worker-crash | poison-message | batch-crash`; each exits non-zero if its
   documented outcome does not hold ([runbook.md](runbook.md#failure-drills)).
4. `./scripts/benchmark.sh` with the stack up: its `http` section adds API p50/p95/p99, event-to-catalog latency and
   queue depth under load to the report; copy the figures to [benchmarking.md](benchmarking.md).
5. `terraform init -backend=false && terraform validate` in `infra/terraform/environments/demo` (CI does this).

## How to continue (for another agent)

- Java verification without Maven: `scratchpad/sandbox-verify.sh` (session-only) compiled the three library
  modules with `javac` against Ubuntu jars; on a normal machine just use Maven.
- Benchmarks: `./scripts/benchmark.sh` (sections and prerequisites in its header). `IngestionBenchmark` (core test
  sources) takes `<file>:<source>[,…] <eventsPerSource> <threads> [matchListings] [latencySamples] [reconcileTarget]`
  and `CATALOGSENTINEL_TEST_JDBC_URL`.
- Every number in the docs must come from a recorded run; update [benchmarking.md](benchmarking.md) with method and
  machine when adding one.
