# Operational runbook

For whoever operates CatalogSentinel, locally or on AWS. Each alert links to a section here. Commands assume the
local stack (`API=http://localhost:8080`); on AWS add `-H "X-Api-Key: $ADMIN_KEY"` to every call, and run SQL
through a bastion or ECS Exec session you have approved.

## Quick reference

| What | Where |
|---|---|
| API | `:8080`, `GET /actuator/health/liveness`, `/actuator/health/readiness` |
| Worker | `:8081`, same probes; readiness includes the database and the queue backlog indicator |
| Dashboard | `:3000`, `GET /healthz`; System health view shows throughput, latency, queues, DLQs, outbox |
| Processing figures | `GET /api/v1/metrics/processing?windowMinutes=60` (throughput, latency by path, queue depths, outbox) |
| Metrics | `/actuator/prometheus`; Prometheus on `:9090` with `docker compose --profile observability up` |
| Traces | Jaeger on `:16686` with the `observability` profile (set `TRACING_SAMPLING_PROBABILITY=1.0`) |
| Logs | `docker compose logs -f catalog-api ingestion-worker`; CloudWatch log groups `/catalogsentinel/<name>/<service>` on AWS |
| Correlation | Search logs for the `X-Correlation-Id` the client received; worker logs also carry `messageId` and `eventId` |

Key log lines: `dead-lettered message queue=… reason="…"`, `retrying message queue=… attempt=…`,
`pausing consumption of … after … consecutive dependency failures`, `dependency healthy again; resuming …`,
`outbox relay: published=… failed=…`, `reconciliation job … claimed by …`, `… failed, retrying in …`,
`canonical product … split: listings … lost their evidence`.

## Start, stop, verify

```bash
docker compose up -d --build --wait          # postgres, elasticmq, moto, api, worker, dashboard
./scripts/seed-demo.sh 10k                   # synthetic demo catalog through the batch job, then a reconciliation
./scripts/smoke.sh                           # end-to-end checks (HTTP, queues, worker, batch); exit 1 on a failure,
                                             # a timeout or a dead letter, with diagnostics
docker compose down                          # keep data;  add -v to delete the database volume
```

## Dead-lettered messages

**Alert**: `DeadLetteredMessages` (Prometheus) or `<name>-<queue>-dlq-not-empty` (CloudWatch).
**Impact**: those events are not applied (listing DLQ) or those listings are not re-matched (change DLQ) until
someone acts. Nothing else is blocked; consumers keep going.

1. Look at the messages without removing them:
   ```bash
   curl -s "$API/api/v1/admin/dead-letters?queue=catalog-listing-events-dlq&max=10"
   ```
2. Read `attributes.failureReason`:

   | Reason prefix | Cause | Fix |
   |---|---|---|
   | `UNKNOWN_SOURCE`, `SOURCE_DISABLED` | Producer sends for a source that is not registered or is disabled | Register or re-enable the source (`POST` or `PATCH /api/v1/sources`) if that is intended, then replay |
   | `INVALID_EVENT`, `MALFORMED_JSON` | Producer bug | Fix the producer and resend corrected events with new event ids; do not replay these |
   | `EVENT_ID_REUSED` | Producer reused an event id for different content | Producer bug; resend with a new id. The first version stays applied |
   | none (no `failureReason`) | Moved by the queue's redrive policy: a worker died repeatedly while holding the message | Check worker logs and restarts around `sentAt`; replay once the worker is healthy |
   | `retries exhausted after 5 attempts: …` | A transient failure (database, dependency, unexpected exception) outlasted the retries | Fix the cause (see [Database](#database)), then replay |

3. Replay to the source queue. Safe to repeat: consumers are idempotent, so already-applied events are no-ops.
   ```bash
   curl -s -X POST "$API/api/v1/admin/dead-letters/replay" -H 'Content-Type: application/json' \
     -d '{"queue":"catalog-listing-events-dlq","maxMessages":100}'
   ```
4. **Verify**: DLQ depth returns to 0 in `GET /api/v1/metrics/processing`; replayed messages that fail again come
   back with the same reason, which means the cause is not fixed.

## Queue backlog

**Alert**: `ListingBacklogGrowing` (more than 5,000 visible for 10 minutes) or
`<name>-<queue>-age` (oldest message older than the configured limit).
**Impact**: listing changes reach the catalog late; dashboards lag. No data is lost.

1. Is the worker consuming? `GET /api/v1/metrics/processing`: per-minute throughput should be non-zero and
   `inFlight` close to the consumer concurrency (8 for listing events, 4 for change events).
2. If throughput is zero: check worker readiness and logs for `pausing consumption` (go to
   [Consumer paused](#consumer-paused)) or for start-up failures.
3. If throughput is steady but below the arrival rate (a bulk import, for example):
   - On AWS, the worker scales out on queue depth automatically up to `worker_max_count`; check the scaling
     activity and raise the limit if it is pinned.
   - Locally, start another worker or raise `LISTING_CONSUMER_CONCURRENCY`. Database CPU is the real limit (see
     [benchmarking.md](benchmarking.md)): if the database is saturated, more consumers will not help.
4. **Verify**: visible messages trend down; the oldest-message age falls.

## Outbox not draining

**Alert**: `OutboxStuck` (oldest pending row older than 5 minutes, or any `FAILED` row) or
`<name>-outbox-oldest-pending`.
**Impact**: listing changes are committed but not announced, so re-matching is delayed. Nothing is lost: rows stay
in the outbox until published.

1. `curl -s "$API/api/v1/admin/outbox"` → `pending`, `failed`, `oldestPendingSeconds`.
2. Worker logs: `outbox relay: published=… failed=…` and `outbox relay tick failed` show SQS errors (permissions,
   endpoint, throttling). The relay backs off per row from 1 s to 5 min and marks a row `FAILED` after 10 attempts.
3. Is the relay running at all? It runs in the worker (`OUTBOX_RELAY_ENABLED`, default true); `outbox relay
   started` appears at start-up.
4. After fixing the cause, give failed rows a fresh set of attempts:
   ```bash
   curl -s -X POST "$API/api/v1/admin/outbox/requeue-failed"
   ```
5. **Verify**: `pending` falls to near 0 and `failed` to 0. Re-publishing a row that SQS already accepted is
   harmless: the matching consumer is idempotent.

## Consumer paused

**Alert**: `ConsumerPaused` (a pause in the last 10 minutes).
**Meaning**: after 5 consecutive outage-type failures (no database connection, transaction could not start) the
consumer stopped polling for 15 s and resumes only when a database probe passes. This is the intended response to
an outage: it stops receive counts from climbing and keeps healthy messages out of the DLQ.

1. Worker logs: `pausing consumption of …` followed by `dependency healthy again; resuming …`. Repeated pauses
   mean the database is still unavailable: go to [Database](#database).
2. If pauses stop on their own, no action is needed: messages that failed during the outage are retried with
   backoff and apply once the database is back.
3. **Verify**: throughput recovers in `GET /api/v1/metrics/processing` and the listing DLQ stays empty.

## API errors

**Alerts**: `<name>-api-5xx` (more than 10 target 5xx responses in a period) and `<name>-api-p95-latency`.

1. Find the failing route in the API logs: `unhandled error on <method> <path>` with the stack trace. With the
   `json-logs` profile (always on in AWS) each line carries the `correlationId` that the client also received in
   the problem body, so a client report leads straight to the log entry.
2. `503 DEPENDENCY_UNAVAILABLE` responses mean the database is unreachable: go to [Database](#database).
3. A `500` is an unexpected exception: the log has the stack trace under that correlation id.
4. High latency with normal error rates: check database CPU (below) and whether a reconciliation is running
   (`GET /api/v1/reconciliation/jobs`); reconciliation shares the API's database pool.
5. **Verify**: the error-rate and latency alarms return to OK.

## Database

**Alerts**: `<name>-db-cpu` (above 80 %), `<name>-db-free-storage` (below 2 GiB), and indirectly every alert above.

- **Unreachable**: nothing restarts (liveness does not depend on the database, by design). The worker fails
  readiness (its readiness group includes the database) and its consumers pause; the API stays ready and answers
  `503 DEPENDENCY_UNAVAILABLE` with `Retry-After`. Restore the database (RDS events, security groups, credentials
  in Secrets Manager); services recover on their own. Locally: `docker compose start postgres`.
- **CPU saturated**: matching is database-bound and dominated by title-trigram retrieval
  ([benchmarking.md](benchmarking.md)). Reduce worker concurrency temporarily or scale the instance class. Find
  the expensive statements:
  ```sql
  SELECT pid, now() - query_start AS running, state, left(query, 120) FROM pg_stat_activity
  WHERE datname = 'catalogsentinel' AND state <> 'idle' ORDER BY running DESC;
  ```
- **Storage**: `processed_events` grows with every event (it is the idempotency ledger and must not be pruned
  while redelivery is possible); published outbox rows are purged after 7 days. Raise allocated storage (autoscaling
  allows up to four times the initial size) and plan archival of old ledger rows.
- **Connections**: each API task uses up to 20 connections and each worker up to 16. Scaling out multiplies this;
  check `max_connections` before raising task counts.

## Procedures

### Resume a failed batch job

The job is idempotent per manifest. Re-run the same command with the same manifest: it re-registers to the same
job, starts attempt + 1, skips partitions that were already published and republishes the rest with the same event
ids (duplicates are absorbed by the ledger). `GET /api/v1/ingestion/jobs/{id}` shows `attempt`, the error history
and `progress.remainingEvents`, derived from the ledger. A job that is `PUBLISHED` is never re-run.

### Reconciliation jobs

Jobs are durable rows in `reconciliation_jobs`, run by a database-backed poller (in every API instance unless
`RECONCILIATION_POLLER_ENABLED=false`). A request only records a `PENDING` job; a poller with a free worker
claims it with `FOR UPDATE SKIP LOCKED` and takes a 5-minute **lease** in database time, renewed after every batch
of work. Any number of instances can poll the same database.

| Situation | What happens on its own |
|---|---|
| Executor busy (all workers running) | The job stays `PENDING` in the database and is claimed when a worker frees up, here or elsewhere |
| API restarted or redeployed | Pollers in the new processes claim `PENDING` jobs at start-up |
| Process crashed or killed mid-run | The lease expires (at most 5 minutes) and any poller claims the job as its next attempt |
| Runner paused past its lease (long GC, network partition) | Another poller takes over; the old runner's writes of status and progress are refused (fencing on `lease_owner`), it stops at its next renewal |
| A retryable error (database, timeout) | The attempt is recorded, the job returns to `PENDING` with `next_attempt_at` 30 s, then 60 s, … (cap 10 min) later |
| Third failed attempt, or a permanent error (for example `REFERENCE_SOURCE_DISABLED`) | `FAILED`, with the last error in `error_message`; the marketplace can be reconciled again |
| Runner died on its last attempt | The next poll or request marks it `FAILED` ("lease expired during attempt 3 of 3 …") |

Inspect: `GET /api/v1/reconciliation/jobs?targetSourceId=…` shows `status`, `attempt`/`maxAttempts`,
`nextAttemptAt`, `leaseExpiresAt` and `errorMessage`. Logs: `reconciliation job … claimed by … (attempt n of m)`,
`… failed, retrying in …s`, `… lease lost to another runner`.

```sql
SELECT job_id, status, attempt, max_attempts, next_attempt_at, lease_owner, lease_expires_at, error_message
FROM reconciliation_jobs WHERE status IN ('PENDING', 'RUNNING') ORDER BY job_id;
```

Operator actions (all safe because reconciliation is idempotent):

- **Retry a waiting job now**: `UPDATE reconciliation_jobs SET next_attempt_at = now() WHERE job_id = … AND status = 'PENDING';`
- **Resume a job whose runner is known to be dead without waiting for its lease**:
  `UPDATE reconciliation_jobs SET lease_expires_at = now() WHERE job_id = … AND status = 'RUNNING';`
  Only after confirming the owner process is gone (`lease_owner` names host, PID and a random suffix).
- **Run again after `FAILED`**: fix the cause, then `POST /api/v1/reconciliation/jobs` (a new job).
- **Stop running jobs in one instance** (for example during an incident): set `RECONCILIATION_POLLER_ENABLED=false`
  on it and redeploy; other instances keep working.

### Reference sources

Gap detection uses only **eligible** reference listings: `ACTIVE` listings of **enabled** reference sources (and of
the selected reference source when a run is scoped to one). Coverage ("carried by n of m reference sources"),
the synthetic demand value and whether a product is a gap at all follow that one rule.

- **Disabling a reference source** (`PATCH /api/v1/sources/{id}` with `{"enabled": false}`): the next
  reconciliation resolves open and confirmed gaps whose products only that source lists, with resolution
  `REFERENCE_SOURCE_DISABLED`; products other enabled sources still list stay gaps with recomputed coverage and
  demand. Dismissed gaps are left as they are.
- **Re-enabling it**: the next reconciliation opens those gaps again as new rows (resolved gaps are history).
- **Merged products**: a gap whose product is merged into another moves to the surviving product and keeps its
  status and history (job statistic `gapsFollowedMerge`). If the survivor already had a gap, that one is kept and
  the other is resolved `MERGED_PRODUCT` with a note in its history (`mergedGapConflicts`, warning in the log):
  review the surviving gap, since decisions are never copied between gaps
  ([ADR 0014](adr/0014-gaps-follow-product-merges.md)).
- **Scoped runs** (`referenceSourceCode` in the request) evaluate only products that source lists, with coverage
  "of 1"; they never resolve gaps that other enabled sources justify. Requesting a disabled source is rejected
  with `422 REFERENCE_SOURCE_DISABLED`; if it is disabled after the request, the job fails visibly.

### Rotate API keys

1. Generate the new key and its entry (see [security.md](security.md#authentication)).
2. Put a value containing both the old and the new entries into the `<name>/api-keys` secret and redeploy the API
   service (`aws ecs update-service --force-new-deployment`); both keys now work.
3. Move clients to the new key, then remove the old entry and redeploy again.

### Deploy and roll back

Deployments go through the approval-gated `deploy` workflow ([deployment.md](deployment.md)). To roll back, run it
with the previous image tag (images are immutable and tagged with the commit), or point the services at the
previous task definition revision. Migrations are additive, so the previous version runs against the newer schema.

## Failure drills

`scripts/inject-failure.sh <scenario>` (`scripts/e2e/drills.py`) provokes each failure against the local stack
and checks the outcome; it exits 0 only if every check holds, 1 with diagnostics (queue depths, outbox, dead
letters, `docker compose ps`, recent worker logs) when a check fails or a wait passes its deadline
(`DRILL_WAIT_SECONDS`, default 300, inside `DRILL_MAX_SECONDS`, 900), and 2 when the stack or the demo sources
(`./scripts/seed-demo.sh`) are missing. Drill data uses the prefix `DRILL-<scenario>-<timestamp>`, so drills can be
repeated and dead letters left by an earlier drill do not fail a later one (depths are compared with a baseline).

| Scenario | What it does | Checked outcome |
|---|---|---|
| `db-outage` | Queues 2,000 events, stops PostgreSQL for 30 s, starts it again | All 2,000 listings land with exactly one ledger entry each; the dead-letter queues did not grow; the worker log's pause and resume lines are reported |
| `worker-crash` | Queues 3,000 events, kills the worker with SIGKILL 3 s later, restarts it after 5 s | In-flight messages reappear after their visibility timeout; 3,000 listings with exactly one ledger entry each; no dead letters |
| `poison-message` | Sends a malformed JSON body (tagged with the drill id) straight to the listing queue | The DLQ grows and holds this message with `failureReason` `MALFORMED_JSON` |
| `batch-crash` | Builds its own 2,000-record export for `contoso-wholesale` (a new manifest, so a new job key), runs the batch job with a crash injected from partition 3, then re-runs it | First run exits non-zero and the job is `FAILED`; the re-run resumes the same job as attempt 2, skips the completed partitions and reports 2,000 events; the job becomes `fullyProcessed` with every event applied exactly once; no dead letters |

The drills have not been run end to end in the environment that built the project (no container images there).
What was run: the drills' own logic (`scripts/e2e/test_drills.py`) and the batch-crash drill's export through the
real PySpark pipeline with the crash injected (`jobs/catalog-batch/tests/test_smoke_export.py`); the recovery
behaviours themselves are covered by `AsyncPipelineIT`, `QueueConsumerTest`, `WorkerPipelineIT` and the batch tests
(see [testing.md](testing.md)). The earlier shell version printed "OK" for `poison-message` and `batch-crash`
without checking the outcome, and its `batch-crash` reused the seeded manifest, whose job was already `PUBLISHED`,
so no crash could happen; both are fixed.
