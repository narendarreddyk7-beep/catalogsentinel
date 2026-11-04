# Repair pass report — branch `repair/verification-2026-11`

What was repaired, how each fix was verified, how to demonstrate it, and what is still open. Every claim points at
evidence in [`docs/evidence/repair-2026-11/`](evidence/repair-2026-11/); the step-by-step record is
[REPAIR_VERIFICATION_LOG.md](REPAIR_VERIFICATION_LOG.md). All data is synthetic. Nothing was deployed, no
`terraform apply` was run and no remote repository was modified.

**Read first:** Maven Central, Docker Hub and the Terraform registry are blocked in the environment this pass ran
in. The Java libraries and all SQL were compiled with `javac -Werror` and tested against a real PostgreSQL 16.15
(substitute harness, **not a Maven build**); the Spring Boot apps were only type-checked against API stubs; the
container stack, smoke test, seed and drills could not run. These are reported as blocked, never as passed.

## 1. Change log, by priority

### P0 — stale match candidates and links (commit `5481644`)

| What | Files | Why |
|---|---|---|
| Currency rule: a candidate counts only while both listings are `ACTIVE` with the content hashes it was scored on; linking, merge discovery, merge re-check, gap evidence and review all use it | `libs/catalog-core/.../matching/MatchCandidateRepository.java` (`CURRENT`, `STALE`, `linkableFor`, `currentLinkableAmong`, `supersedeStaleFor`, `lockCurrency`), `CanonicalProductRepository.java` (`findMergeablePairs`, `merge` re-check under locks, `lockActiveProducts`, `activeMembers`), `gaps/GapRepository.java` (`BEST_CANDIDATE_SQL`) | Links, merges, gap evidence and approvals were justified by pairs scored on content that no longer existed (5 of 6 scenarios failed) |
| Re-matching supersedes stale candidates of every status; a superseded human decision gets a `SUPERSEDE` audit row by `system:matching` | `MatchingService.java`, `db/migrations/V5__match_evidence_currency.sql` | Withdrawal superseded only `PENDING` candidates; human decisions must not vanish silently |
| A product a listing leaves is regrouped into connected components of current evidence (union-find); the representative's group keeps the id; products are locked in id order with retry | `MatchingService.java` (`link`, `detach`, `regroup`, `justify`, `components`) | A product held together only by a changed listing stayed one product; a changed representative rewrote the product of unchanged members |
| Review refused with `409 CANDIDATE_STALE` while a listing changed or was withdrawn since scoring | `review/ReviewService.java`, `apps/catalog-api/.../web/ReviewController.java`, `docs/api.md` | A reviewer could approve a comparison of content that no longer existed |
| Regression tests and decision record | `StaleMatchEvidenceIT.java` (6), `CoreTestContext.java`, `docs/adr/0013-match-evidence-currency.md` | Red first (5 of 6), then green |

### P1 — reference-source eligibility (commit `df2cdde`)

| What | Files | Why |
|---|---|---|
| One eligibility predicate (ACTIVE listing of an ENABLED reference source, plus the selected source in a scoped run) for products, reference listings, coverage numerator, demand and `HAVING`; denominator from `referenceSourceScope` | `gaps/GapRepository.java` | A disabled source's exclusive products stayed gaps; coverage and demand counted disabled sources |
| Stale gaps resolved globally: `MERGED_PRODUCT`, `REFERENCE_WITHDRAWN`, `REFERENCE_SOURCE_DISABLED`; re-enabling reopens as new rows | `GapRepository.invalidatedGaps`, `ReconciliationService.java` | Gaps must follow the policy both ways |
| Selecting a disabled source is `422 REFERENCE_SOURCE_DISABLED`; disabled after the request, the job fails permanently and visibly | `ReconciliationService.request`, `runClaimed` | A scoped run over nothing must not silently succeed |
| Tests | `ReferenceSourceEligibilityIT.java` (5) | Red first (4 of 5) |

### P1 — durable reconciliation jobs (commit `5c738c7`)

| What | Files | Why |
|---|---|---|
| Jobs are claimed from the database (`FOR UPDATE SKIP LOCKED`) with a lease in database time renewed after every batch; renew/complete/fail are fenced on `lease_owner`; retryable failures back off 30 s × 2^(n−1) (cap 10 min) up to 3 attempts; crashed last attempts fail visibly | `db/migrations/V6__durable_reconciliation_jobs.sql`, `gaps/ReconciliationJobRepository.java`, `gaps/ReconciliationService.java`, `gaps/ReconciliationJobPoller.java`, `MatchingService.matchPending(…, afterBatch)` | An in-memory executor could reject or lose jobs on saturation, restart or crash; abandoned runs were only noticed by the next request |
| The API hosts the poller as a `SmartLifecycle` bean (threads, interval, per-instance on/off); requests never run reconciliation on the request thread | `apps/catalog-api/.../config/ReconciliationRunner.java`, `CatalogApiProperties.java`, `application.yml`, `web/ReconciliationController.java` (attempt, lease fields) | Long work off request threads; several instances can poll one database |
| Tests and runbook | `ReconciliationJobDurabilityIT.java` (8), `GapReconciliationIT.abandonedJobsAreRecovered` (rewritten, same intent), `docs/runbook.md#reconciliation-jobs` | Restart, crash, fencing, retry, bounds, saturation, two pollers |

### P1 — gaps follow product merges (commit `a84b61f`), found by the smoke scenario

| What | Files | Why |
|---|---|---|
| Before evaluation and before invalidation, a live gap on a merged-away product moves to the survivor with its id, status and history; if the survivor already has a gap, the other is resolved `MERGED_PRODUCT` with the conflict in its audit trail; decisions are never copied between gaps | `gaps/GapRepository.java` (`liveGapsOnMergedProducts`, `findLiveGap`, `moveToProduct`), `gaps/ReconciliationService.java` (`followMerges`), `docs/adr/0014-gaps-follow-product-merges.md` | With the marketplace's change events first, approving a match merged the reference product away: a dismissal stopped suppressing, a confirmation was replaced by a new OPEN gap, a carried product's gap closed as `MERGED_PRODUCT` |
| The smoke test's scenario as a shared fixture, run in process for 3 tokens and both change orders | `scripts/e2e/smoke-scenario.json`, `core/scenario/SmokeScenario.java`, `SmokeScenarioIT.java` | The smoke test's expected outcomes are checked against the real code |
| Tests | `GapsFollowProductMergesIT.java` (5) | Red first (4 of 4) |

### P1 — real dependencies (commits `f8f8e14`, `1cfa8cb`)

Every command was attempted and recorded: `mvn -B -ntp verify` (blocked), `npm ci`/`test`/`build` (pass),
`pytest` (pass), `docker compose config` (pass), `docker compose up -d --build --wait` (blocked), smoke, seed and
the four drills (attempted; fail fast against the absent stack). No real compatibility failure could be observed
because nothing compiled against the real Spring Boot libraries; that remains the first step on a networked
machine.

### P1/P2 — scripts (commits `dcb1832`, `58e2af8`, `1cfa8cb`)

| What | Files | Why |
|---|---|---|
| `smoke.sh` → `scripts/e2e/smoke.py`: per-run token (own sources, brand, category, GTINs); exact checks for create/replay/reused id/stale/conflict/withdraw/reactivate, queued redelivery without duplicates, convergence on one canonical id, the exact live gaps and coverage, audited idempotent gap and match decisions, a batch job `PUBLISHED` + `fullyProcessed` + no-op re-run, filtered queries and aggregates; deadlines per wait and per run; dead-letter growth and timeouts exit 1 with diagnostics; works behind the AWS load balancer's `/actuator` block | `scripts/smoke.sh`, `scripts/e2e/smoke.py`, `smoke-scenario.json`, `test_smoke.py`, `.github/workflows/ci.yml` (pipefail, smoke output artifact) | The old script checked status codes of a few endpoints and could pass with wrong data |
| `seed-demo.sh` → `seed_demo.py`: deterministic exports, job keys and event ids; reconciliation `Idempotency-Key` from the job keys; waits for every job's `fullyProcessed` and drained queues; hard limit per size; diagnostics | `scripts/seed-demo.sh`, `scripts/e2e/seed_demo.py`, `test_seed_demo.py` | The old wait loop ended silently after 15 minutes and the reconciliation was fire-and-forget |
| `inject-failure.sh` → `drills.py`: each drill checks its outcome and exits non-zero otherwise | `scripts/inject-failure.sh`, `scripts/e2e/drills.py`, `test_drills.py`, `jobs/catalog-batch/tests/test_smoke_export.py` | `poison-message` and `batch-crash` printed OK without checking; `batch-crash` reused a `PUBLISHED` manifest, so no crash could happen; `db-outage` failed after an earlier poison drill |

### P2 — hardening (commit `f8f8e14`)

| What | Files | Why |
|---|---|---|
| Health details only for `ADMIN` keys (`when-authorized`, role ADMIN); `always` refused at start-up while security is enabled | `apps/catalog-api/src/main/resources/application.yml`, `security/HealthDetailsPolicy.java`, `SecurityConfiguration.java`, `HealthDetailsPolicyTest.java`, `SecuredApiIT.healthDetailsAreShownOnlyToAdminKeys` | Anonymous callers saw database and disk details |
| Every published compose port on `127.0.0.1` (`BIND_ADDRESS`); local mode's lack of authentication documented | `infra/compose/compose.yaml`, `docs/security.md` | The local stack has no authentication and fixed credentials |
| A plan-only deploy run no longer logs in to ECR, builds or pushes | `.github/workflows/deploy.yml` | `apply=false` still published images |
| `pom.xml` pointed at a non-existent ADR; now ADR 0006; no broken doc link remains | `pom.xml` | Broken reference |
| ESCI stays absent (ADR 0009); Terraform: hcl2 parse + checkov 247/0; `terraform validate` blocked | — | — |

### Benchmarks and documentation (this commit)

`scripts/benchmark.sh` rewritten to record an environment profile and run every section the machine allows, with
`SKIPPED`/`FAILED` stated; `IngestionBenchmark` gained in-process event-to-link latency and a timed
reconciliation; `scripts/bench/repair-plans.sql` explains the new statements; `scripts/e2e/http_bench.py` measures
HTTP and event-to-catalog latency once the stack runs. Updated: `IMPLEMENTATION_STATUS.md`, `testing.md`,
`benchmarking.md`, `runbook.md`, `security.md`, `architecture.md`, `database.md`, `api.md`, `resume-bullets.md`,
`interview-guide.md`, `demo-script.md`, `README.md`, ADRs 0013 and 0014.

## 2. Verification matrix

| Command | Result | Evidence |
|---|---|---|
| `StaleMatchEvidenceIT` against the pre-fix commit | **Failed as intended: 5 of 6** | `01-p0-red-StaleMatchEvidenceIT.log` |
| `ReferenceSourceEligibilityIT` against the P0 commit | **Failed as intended: 4 of 5** | `02-p1-red-ReferenceSourceEligibilityIT.log` |
| `GapsFollowProductMergesIT` before its fix | **Failed as intended: 4 of 4** | `04-red-GapsFollowProductMergesIT.log` |
| Full Java suite (domain, contracts, core) — javac harness + PostgreSQL 16.15, not Maven | **Passed: 242 / 242** | `17-final-full-suite.log` (earlier: `03`, 233; `05`, 242) |
| App type-check against stubs (platform-adapters, API, worker, their tests) | **Passed** (`== OK`); not a build against the real libraries | `18-final-typecheck.log` |
| `HealthDetailsPolicyTest`, `ApiKeyAuthenticationFilterTest` | **Passed: 3 / 3** | `07-api-unit-tests.log` |
| `mvn -B -ntp verify` | **Not run: blocked** (repo.maven.apache.org 403) | `10-maven-verify.log` |
| `npm ci`, `npm test`, `npm run build` | **Passed: 17 / 17**, build OK | `11-dashboard-npm.log` |
| Batch `pytest` | **Passed: 891 / 891** | `12-python-tests.log` |
| `pytest scripts/e2e` | **Passed: 33 / 33** | `19-final-tooling-and-lint.log` |
| `docker compose config --quiet` (all profiles) | **Passed**; all ports on 127.0.0.1 | `09-static-checks.log`, `19-…` |
| `docker compose up -d --build --wait` | **Not run: blocked** (registry-1.docker.io Forbidden) | `13-compose-up.log` |
| `./scripts/smoke.sh` | **Not run against a stack**; against the absent stack: exit 1 at readiness, within its deadline | `14-stack-scripts-without-stack.log` |
| `./scripts/seed-demo.sh 10k` | **Not run against a stack**; exit 1 at readiness, within its deadline | `14-…` |
| `./scripts/inject-failure.sh worker-crash / poison-message / db-outage / batch-crash` | **Not run against a stack**; each exit 1 at readiness | `14-…` |
| Smoke scenario expectations in process (3 tokens, both orders) | **Passed: 4 / 4** (`SmokeScenarioIT`) | `17-…` |
| Smoke and drill exports through the real PySpark pipeline (crash + resume) | **Passed: 2 / 2** (`test_smoke_export.py`, part of the 891) | `12-python-tests.log` |
| `terraform fmt` / `init -backend=false` / `validate` | **Not run: blocked** (no binary; registry refused) | `08-terraform-checks.log` |
| python-hcl2 parse; `checkov` | **Passed**: 22 files; 247 passed, 0 failed | `08-…` |
| `actionlint`, `shellcheck`, `pyflakes` | **Passed** (clean) | `09-…`, `19-…` |
| `./scripts/benchmark.sh` (env, generate, quality, spark, ingest, match, latency, plans; http skipped: no stack) | **Ran**; figures in [benchmarking.md](benchmarking.md#re-measured-after-the-repair-pass-2026-11-03-scriptsbenchmarksh) | `15-benchmark-report.md`, `15-query-plans.txt`, `15b`, `15c` |
| Matching throughput before vs after the repair (A/B) | **Ran**: no measurable difference (after 117.6–146.9 s, before 137.6–147.1 s for 3,000 listings; identical products) | `16-matching-ab-before-after.log` |

## 3. Demo playbook

On a machine with Docker Hub and Maven Central access:

```bash
mvn -B -ntp verify                                   # first: the real build, all Testcontainers ITs
docker compose up -d --build --wait                  # local mode: no auth, ports on 127.0.0.1
./scripts/seed-demo.sh 10k                           # deterministic; a second run changes nothing
./scripts/smoke.sh                                   # exact end-to-end checks; exit 0 or a diagnosed failure
./scripts/inject-failure.sh poison-message           # MALFORMED_JSON in the DLQ
./scripts/inject-failure.sh worker-crash             # SIGKILL mid-run; 3,000 listings, one ledger entry each
./scripts/benchmark.sh                               # adds HTTP p50/p95/p99 and event-to-catalog latency
```

Then follow [demo-script.md](demo-script.md) (about 4½ minutes), including its optional "evidence stays current"
segment: change a listing's brand and model, show it leave its canonical product and its old pair `SUPERSEDED`.
Talking points for the repair itself are in [interview-guide.md](interview-guide.md) (questions 15a and 15b).

## 4. Remaining risks and minimal next steps

| Risk | Minimal next step |
|---|---|
| The Spring Boot 4.1 / Security 7 / Jackson 3 / Testcontainers 2 / AWS SDK paths were never compiled against the real libraries; `SecuredApiIT` (health details per role), `CatalogApiIT`, `WorkerPipelineIT` never ran | `mvn -B -ntp verify` on a networked machine; fix API drift |
| The stack, smoke test, seed and drills never ran; HTTP-level details (status codes, headers, field names) were checked only against the controller code | `docker compose up … && ./scripts/seed-demo.sh 10k && ./scripts/smoke.sh`, then the four drills |
| Merge discovery reads the whole catalog (77.5 ms at 5,500 listings, linear) and also runs after every approval | Scope the post-approval merge to the two products; add a partial index on linkable candidates |
| The reconciliation poller runs in the API process and shares its connection pool | Run it in the worker or a dedicated task (`RECONCILIATION_POLLER_ENABLED=false` on the API) |
| Two products that both had live gaps when merged keep only the survivor's; the other's decisions are recorded, not carried over (ADR 0014) | Product decision; a reviewer re-decides the surviving gap |
| HTTP p50/p95/p99, queue age, event-to-catalog latency through SQS and recovery times are not measured | `./scripts/benchmark.sh` (section `http`) and the drills on the running stack |
| `terraform validate` never ran here | CI's `infrastructure` job, or locally with the registry reachable |

## 5. Portfolio summary (Amazon SDE II)

CatalogSentinel is a personal project on synthetic data: a Java 21 / Spring Boot / PostgreSQL / SQS system that
ingests marketplace and reference catalogs at least once with an idempotency ledger and transactional outbox,
resolves listings to canonical products with an explainable rule-based matcher, and ranks the reference products
a marketplace is missing. In a repair pass I wrote failing PostgreSQL integration tests first and fixed what they
exposed: match evidence that outlived the content it judged (5 of 6 scenarios), gap detection that counted
disabled reference sources (4 of 5), and review decisions lost when products merged (4 of 4, found by running the
end-to-end scenario in a different event order). I replaced in-memory job execution with a leased, fenced,
retried PostgreSQL job queue, rebuilt the smoke, seed and failure-drill scripts to check exact outcomes with
deadlines, and re-measured: 756–852 events/s ingestion on 2 vCPUs, in-process event-to-link p99 30 ms, every new
query under 6 ms except one identified bottleneck. 242 Java tests, 891 batch tests and 33 tooling tests pass in
the build sandbox; the Spring Boot services and the container stack could not be built there (Maven Central and
Docker Hub blocked), which is stated wherever results are reported. It has not been deployed.
