# Repair verification log

Every verification step of the repair pass on branch `repair/verification-2026-11`, in the order it was run:
the command, when (UTC, 2026-11-03), the tool versions, the result and where the evidence is. Steps 3–8 and 12–15
ran before evidence files were kept; their times come from log-file modification times or the following commit,
the original output of the two full runs among them was copied into `00a` and `00b`, and the red runs were
re-created from the pre-fix commits with full output (steps 9, 10, 13). Nothing
here is inferred; "not run" means the step could not be executed in this environment, with the reason. Evidence files are
in [`docs/evidence/repair-2026-11/`](evidence/repair-2026-11/) unless stated otherwise; each starts with its
command, commit, tool versions and start time, and ends with its finish time and exit status.

## Environment

| | |
|---|---|
| Host | Linux 6.18.44, 2 vCPU Intel Xeon @ 2.10 GHz, 8,027 MiB RAM (cloud sandbox, shared) |
| Java | OpenJDK 21.0.12.1 (`javac 21.0.12.1`) |
| Maven | Apache Maven 3.9.11 — installed, but cannot resolve anything (Maven Central blocked, below) |
| Node / npm | v22.23.4 / 10.9.9 |
| Python | 3.13.18 (PySpark 4.2.1, moto 5.2.5, pytest 9.2.0 in the batch test venv) |
| PostgreSQL | 16.15 (Ubuntu package), local cluster, default configuration (`shared_buffers` 128MB, `random_page_cost` 4, `synchronous_commit` on) |
| Docker | Engine and CLI 29.9.2, Compose 5.6.1; the daemon starts (`dockerd --iptables=false --bridge=none`) but cannot pull images |
| Terraform | not installed; the HashiCorp release host and provider registry are blocked |
| Linters | shellcheck 0.9.0, actionlint 1.7.12, pyflakes, checkov 3.4.3 |

### Network policy (evidence)

Run at 16:01 and again at 20:16–20:22 through the session's egress proxy:

```text
$ curl -sS -v https://repo.maven.apache.org/maven2/ -o /dev/null
> CONNECT repo.maven.apache.org:443 HTTP/1.1
< HTTP/1.1 403 Forbidden
curl: (56) CONNECT tunnel failed, response 403
$ curl -sS -v https://registry-1.docker.io/v2/ -o /dev/null
< HTTP/1.1 403 Forbidden
proxy status: repo.maven.apache.org:443 — connect_rejected; registry-1.docker.io:443 — connect_rejected
$ curl https://releases.hashicorp.com/terraform/                      -> 000 (refused)
$ curl https://registry.terraform.io/v1/providers/hashicorp/aws/versions -> 000 (refused)
$ curl https://registry.npmjs.org/react                               -> 200
$ curl https://pypi.org/simple/pyspark/                               -> 200
```

Consequence: `mvn -B -ntp verify`, Testcontainers, `docker compose up`, the smoke test, the seed, the failure drills
and `terraform init/validate` cannot run here. Following the proxy's instructions, the blocked hosts were reported
and not routed around (no mirrors, no alternative registries). Java verification uses a substitute harness:
`javac -Werror` against Ubuntu-packaged Spring JDBC/TX 4.3 jars plus JUnit 5 on the console launcher, against a
real PostgreSQL 16.15 with the migration files applied by `psql`, one transaction per file. **That is not a Maven
build** and is never reported as one. The Spring Boot applications are only type-checked against API stubs.

## Steps

| # | Time (UTC) | Command | Result | Evidence |
|---|---|---|---|---|
| 1 | 16:01 | `curl` probes above | Maven Central, Docker Hub, HashiCorp blocked (403 / refused); npm, PyPI reachable | this file, "Network policy" |
| 2 | 16:02 | `git checkout -b repair/verification-2026-11` | Branch created from `e63e4b7` | `git log` |
| 3 | before 16:20 | `CLASS_FILTER='.*StaleMatchEvidenceIT$' sandbox-verify.sh` on the unmodified code (P0 red run) | **5 of 6 failed**: changed listing stays linked by old evidence; changed representative rewrites the shared product; withdrawal leaves the candidate `AUTO_ACCEPTED`; stale approval accepted (no `CANDIDATE_STALE`); product held together by a changed listing not split. The redelivery test passed. Output not kept; re-created in step 9 | step 9 |
| 4 | 16:19 (log file time) | full harness after the P0 fix | 220 / 220 passed; `StaleMatchEvidenceIT` 6 / 6; DB matching and gap evaluation unchanged | `00a-full-suite-after-p0-original.log`; commit `5481644` |
| 5 | before commit `df2cdde` (16:24) | `CLASS_FILTER='.*ReferenceSourceEligibilityIT$'` on the P0 code (P1 red run) | 3 of 4 failed (the fifth test was written afterwards); re-created with all five in step 10 | step 10 |
| 6 | before commit `df2cdde` (16:24) | targeted harness after the eligibility fix | `ReferenceSourceEligibilityIT` 5 / 5 (the fifth test, a source disabled after the request, was added with the fix); the full suite next ran in step 7 | commit `df2cdde` |
| 7 | 16:33 (log file time) | full harness after durable jobs (V6) | 233 / 233 passed; `ReconciliationJobDurabilityIT` 8 / 8, `GapReconciliationIT` 9 / 9 | `00b-full-suite-after-durable-jobs-original.log`; commit `5c738c7`; step 11 |
| 8 | before commit `5c738c7` (16:37) | `typecheck-apps.sh` (API runner, controller, properties) | `== OK` (stubs); re-run in step 16 | step 16 |
| 9 | 16:44 | P0 red run re-created: `StaleMatchEvidenceIT` from `5c738c7` against a worktree of the pre-fix commit `e63e4b7`; one adaptation: `MatchingService.SYSTEM_REVIEWER` (added by the fix) replaced by its literal | **5 of 6 failed**, same five as step 3; harness exit 1 | `01-p0-red-StaleMatchEvidenceIT.log` |
| 10 | 16:44 | P1 red run re-created: `ReferenceSourceEligibilityIT` (unchanged) against a worktree of `5481644` | **4 of 5 failed**; the baseline test (all sources enabled) passed; exit 1 | `02-p1-red-ReferenceSourceEligibilityIT.log` |
| 11 | 16:44–16:47 | full harness at `5c738c7` | **233 / 233 passed**, exit 0; DB matching: retrieval recall 1.0, cluster P 1.0 R 0.456; gap evaluation unchanged (54 open, confident P 0.778 R 0.737) | `03-green-full-suite.log` |
| 12 | before commit `a84b61f` (17:06) | `SmokeScenarioIT` (new) on 3 run tokens | 4 / 4 passed; with the marketplace's change events first: **failed** — gap resolved `MERGED_PRODUCT` instead of `AUTO_MATCH_FOUND` (defect found) | harness output, then step 13 |
| 13 | 17:00 | `GapsFollowProductMergesIT` (new) before the fix | **4 of 4 failed**: dismissal replaced by a new OPEN gap; confirmation replaced; merged-into-carried resolved `MERGED_PRODUCT`; conflict not recorded; exit 1 | `04-red-GapsFollowProductMergesIT.log` |
| 14 | 17:02–17:05 | full harness after ADR 0014 | **242 / 242 passed**, exit 0 (incl. `GapsFollowProductMergesIT` 5, `SmokeScenarioIT` 4 with both orders); evaluations unchanged | `05-green-full-suite-after-merge-fix.log` |
| 15 | before commit `dcb1832` (17:13) | `pytest scripts/e2e`; `pytest tests/test_smoke_export.py` (batch); `shellcheck`, `pyflakes` | 25 passed; 1 passed (smoke export through the real PySpark pipeline); lint clean | commit `dcb1832` |
| 16 | 20:15 | `typecheck-apps.sh` after P2 | `== OK` (32 main files, API tests incl. `SecuredApiIT` and `HealthDetailsPolicyTest`); stubs, not the real libraries | `06-typecheck-apps.log` |
| 17 | 20:15 | console launcher: `HealthDetailsPolicyTest`, `ApiKeyAuthenticationFilterTest` | 3 / 3 passed | `07-api-unit-tests.log` |
| 18 | 20:16 | `command -v terraform`; registry probes; python-hcl2 parse; `checkov --skip-download` | terraform **not run** (no binary; registry refused); 22 files parse; checkov **247 passed, 0 failed**, 16 justified skips in config | `08-terraform-checks.log` |
| 19 | 20:16 | `docker compose config --quiet` (all profiles), port bindings, `actionlint`, `shellcheck`, `pyflakes` | Valid; all 10 published ports on 127.0.0.1, `BIND_ADDRESS` override works; all linters clean | `09-static-checks.log` |
| 20 | 20:21 | `mvn -B -ntp verify` | **Not run: blocked.** Non-resolvable parent POM `spring-boot-starter-parent:4.1.2`; proxy refuses repo.maven.apache.org (403); exit 1 | `10-maven-verify.log` |
| 21 | 20:21 | `npm ci`, `npm test`, `npm run build` (dashboard) | All succeed; **17 / 17** tests; build in 2.2 s | `11-dashboard-npm.log` |
| 22 | 20:22 | `pytest -q` (batch); `pytest -q scripts/e2e` | **891 / 891** passed (66 s); 30 / 30 passed | `12-python-tests.log` |
| 23 | 20:22 | `docker compose up -d --build --wait` | **Not run: blocked.** Daemon up; every pull refused (`registry-1.docker.io … Forbidden`); exit 1 | `13-compose-up.log` |
| 24 | 20:23 | `smoke.sh`, `seed-demo.sh 10k`, `inject-failure.sh` × 4 against the absent stack | Each fails at readiness within its deadline: exit 1 with diagnostics; usage error exit 2. **Verifies nothing about the stack** | `14-stack-scripts-without-stack.log` |
| 25 | 20:30–20:37 | `scripts/benchmark.sh` (sandbox driver sets `BENCH_CLASSPATH`, `EVAL_CLASSPATH`, `BATCH_PYTHON`, `BENCH_JDBC_URL`, `BENCH_PSQL`) | All sections ran except `http` (SKIPPED: no stack). Ingestion 756 / 783 / 852 events/s; matching 3,000 listings 124.6 s; event-to-link p50 17.9 ms, p99 30.0 ms; reconciliation of 2,734 products 1.7 s; 8 query plans | `15-benchmark-report.md`, `15-query-plans.txt`, `15c-benchmark-raw-section-output.txt` |
| 26 | 20:37 | `BENCH_SECTIONS=env scripts/benchmark.sh` after fixing two cosmetic errors in the environment profile | Corrected profile (stack "not running", `shared_buffers=128MB`) | `15b-benchmark-env-profile-rerun.md` |
| 27 | 20:37–20:48 | Matching A/B: `IngestionBenchmark … 10000 8 3000` alternating on builds of `e63e4b7` and of the repaired code | Before 137.6 / 147.1 s, after 146.9 / 117.6 s for 3,000 listings; 2,996 products every run: no measurable difference | `16-matching-ab-before-after.log` |
| 28 | 20:49–20:52 | Full harness at the end of the pass | **242 / 242 passed**, exit 0; DB matching and gap evaluation unchanged. Run on the working tree that became the final commit (the code was not changed afterwards, only documentation and evidence files) | `17-final-full-suite.log` |
| 29 | 20:52 | `typecheck-apps.sh` | `== OK` (stubs, not the real libraries) | `18-final-typecheck.log` |
| 30 | 20:52 | `pytest scripts/e2e`; `shellcheck`; `actionlint`; `pyflakes`; `docker compose config` | 33 / 33 passed; shellcheck, actionlint, pyflakes clean; compose config valid for all profiles | `19-final-tooling-and-lint.log` |

## Not run, and why

| Step | Reason | What would run it |
|---|---|---|
| `mvn -B -ntp verify` (all modules, Testcontainers ITs incl. `SecuredApiIT`, `CatalogApiIT`, `WorkerPipelineIT`) | Maven Central blocked (403) | Any machine with Maven Central; CI job `java` |
| `docker compose up -d --build --wait`, image builds | Docker Hub blocked (403) | Any machine with Docker Hub; CI job `images` |
| `./scripts/smoke.sh`, `./scripts/seed-demo.sh 10k`, `./scripts/inject-failure.sh` × 4 against the stack | No stack (above) | After `docker compose up`; CI runs the smoke test |
| `terraform fmt -check`, `init -backend=false`, `validate` | No Terraform binary; HashiCorp hosts blocked | CI job `infrastructure` |
| HTTP p50/p95/p99, queue age, event-to-catalog latency through SQS, recovery times | No stack | `./scripts/benchmark.sh` section `http`; the drills |

