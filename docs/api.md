# REST API

Versioned under `/api/v1`, JSON in and out, served by `apps/catalog-api` on port 8080.

- **OpenAPI**: generated at runtime by springdoc from the annotated controllers: `GET /v3/api-docs` (JSON),
  `GET /v3/api-docs.yaml`, interactive UI at `/swagger-ui.html`. CI saves each build's document as the `openapi`
  artifact. The environment this project was built in could not run the services, so no generated copy is
  committed; the tables below are written from the controller code.
- **Examples** assume the local stack (`docker compose up --build`) with security disabled. All data is
  synthetic.

## Conventions

| Topic | Rule |
|---|---|
| Authentication | Disabled locally. In the `aws` profile every `/api/**` call needs `X-Api-Key`; see [security.md](security.md) for roles. `401 UNAUTHENTICATED` without a valid key, `403 FORBIDDEN` without the role. |
| Errors | [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details (`application/problem+json`) with a stable machine-readable `code`, the request's `correlationId`, and `errors: [{field, message}]` listing every invalid field at once. |
| Correlation | Send `X-Correlation-Id` to trace a request across the API, the queue and the worker; one is generated otherwise and always echoed in the response. |
| Idempotency | Listing events are idempotent by `eventId`. Reconciliation requests and review decisions accept an `Idempotency-Key` header: a retry with the same key returns the original result. Batch jobs are idempotent by `jobKey`. |
| Pagination | Collections return `{items, page: {limit, nextCursor, hasMore}}`. Pass `cursor=<nextCursor>` for the next page. `limit` defaults per endpoint and is capped at 200. Cursors are opaque and versioned; a malformed one is `400 INVALID_CURSOR`. |
| Time and numbers | Timestamps are ISO-8601 UTC. Scores and confidences are decimals in [0, 1]; priority scores in [0, 100]. |
| Availability | A database outage surfaces as `503 DEPENDENCY_UNAVAILABLE` with `Retry-After: 5`. |

## Endpoints

Role column: the roles that may call the endpoint when security is enabled (`ADMIN` may call everything).

### Sources

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `POST /api/v1/sources` | Register a marketplace or reference catalog | ADMIN | 201 + `Location`; 400; 409 `SOURCE_CODE_TAKEN` |
| `GET /api/v1/sources` | List; filters `role`, `enabled`; default limit 50 | any | 200 |
| `GET /api/v1/sources/{sourceId}` | One source | any | 200; 404 |
| `PATCH /api/v1/sources/{sourceId}` | Enable or disable (`{"enabled": false}`) | ADMIN | 200; 404 |

### Ingestion

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `POST /api/v1/listing-events` | Apply one event synchronously | INGEST | 201 new listing; 200 update, no-op, stale or replay (`Idempotent-Replay: true`); 400; 409 `VERSION_CONFLICT` or `EVENT_ID_REUSED`; 422 `UNKNOWN_SOURCE` or `SOURCE_DISABLED` |
| `POST /api/v1/ingestion/events` | Validate 1–500 events, then queue them | INGEST | 202 all queued; 400 nothing queued; 503 with the ids the queue rejected (resubmitting the batch is safe) |
| `POST /api/v1/ingestion/jobs` | Register a batch job (idempotent per `jobKey`) | INGEST | 201 new; 200 existing; 409 `JOB_KEY_REUSED`; 422 `UNKNOWN_SOURCE` |
| `PATCH /api/v1/ingestion/jobs/{jobId}` | Report `RUNNING`, `PUBLISHED` or `FAILED` with counts and errors | INGEST | 200; 409 `INVALID_JOB_TRANSITION` or `JOB_ALREADY_PUBLISHED` |
| `GET /api/v1/ingestion/jobs` | Import history; filters `status`, `sourceCode`; default limit 20 | any | 200 |
| `GET /api/v1/ingestion/jobs/{jobId}` | Job with manifest, error history and progress derived from the ledger | any | 200; 404 |

### Listings and canonical products

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `GET /api/v1/listings` | Search; filters `sourceId`, `sourceCode`, `status`, `brand`, `category`, `gtin`, `q` (title text), `linked`; default limit 50 | any | 200 |
| `GET /api/v1/listings/{listingId}` | Listing with raw and normalized fields, canonical link, recent processed events | any | 200; 404 |
| `GET /api/v1/canonical-products` | Search; filters `q`, `brand`, `category` | any | 200 |
| `GET /api/v1/canonical-products/{id}` | Product with its linked listings | any | 200; 404 |

### Matching review

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `GET /api/v1/match-candidates` | Candidate pairs with feature scores and evidence; filters `reviewStatus`, `decision`, `minScore`, `sourceId`, `listingId` | any | 200 |
| `GET /api/v1/match-candidates/{id}` | One pair, both listings side by side | any | 200; 404 |
| `POST /api/v1/match-candidates/{id}/decisions` | `APPROVE` (link both listings to one product) or `REJECT` (split an auto-accepted pair) | REVIEWER | 200; 404; 409 `CANDIDATE_NOT_REVIEWABLE` (superseded) or `CANDIDATE_STALE` (a listing changed or was withdrawn since scoring) |

### Gaps and reconciliation

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `POST /api/v1/reconciliation/jobs` | Record a gap-detection job for a marketplace (`targetSourceCode`, optional `referenceSourceCode`); a poller runs it | ADMIN | 202 + `Location`; 409 `RECONCILIATION_IN_PROGRESS`; 422 `REFERENCE_SOURCE_DISABLED` |
| `GET /api/v1/reconciliation/jobs` | Runs, newest first; filter `targetSourceId` | any | 200 |
| `GET /api/v1/reconciliation/jobs/{id}` | Status, attempts, lease and statistics | any | 200; 404 |
| `GET /api/v1/gaps` | Priority-ranked gaps; filters `targetSourceId`, `status`, `priority`, `reason`, `category`, `categoryRoot`, `minConfidence` | any | 200 |
| `GET /api/v1/gaps/{id}` | Gap with evidence and its audit history | any | 200; 404 |
| `POST /api/v1/gaps/{id}/decisions` | `CONFIRM`, `DISMISS`, `RESOLVE` or `REOPEN` | REVIEWER | 200 (`changed: false` when already in that state); 404; 409 `INVALID_TRANSITION` |

### Monitoring and operations

| Method and path | Purpose | Role | Responses |
|---|---|---|---|
| `GET /api/v1/dashboard/summary` | Totals: sources, listings, products, candidates, gaps, events in the last 24 h by outcome, outbox backlog, last reconciliation | any | 200 |
| `GET /api/v1/dashboard/coverage` | Per reference category: products carried by the target, open, confirmed and dismissed gaps, not yet evaluated (`targetSourceId` or `targetSourceCode`, marketplace only) | any | 200; 400; 404 |
| `GET /api/v1/metrics/processing` | Per-minute throughput by outcome, latency percentiles by ingestion path, outbox and queue depths (`windowMinutes`, default 60) | any | 200 |
| `GET /api/v1/admin/dead-letters?queue=…-dlq&max=10` | Peek at dead-lettered messages without removing them | ADMIN | 200; 400 |
| `POST /api/v1/admin/dead-letters/replay` | Move up to 1,000 messages back to the source queue | ADMIN | 200 |
| `GET /api/v1/admin/outbox` | Pending and failed rows, oldest pending age | ADMIN | 200 |
| `POST /api/v1/admin/outbox/requeue-failed` | Give `FAILED` outbox rows a fresh set of attempts | ADMIN | 200 |
| `GET /actuator/health/liveness`, `/readiness` | Probes | public | 200; 503 |
| `GET /actuator/prometheus` | Metrics scrape | public in the app, blocked at the load balancer | 200 |

## Examples

### Register sources

```bash
curl -s -X POST localhost:8080/api/v1/sources -H 'Content-Type: application/json' \
  -d '{"code":"acme-market","name":"Acme Market","role":"MARKETPLACE"}'
curl -s -X POST localhost:8080/api/v1/sources -H 'Content-Type: application/json' \
  -d '{"code":"northwind-supply","name":"Northwind Supply","role":"REFERENCE"}'
```

```json
{"sourceId": 1, "code": "acme-market", "name": "Acme Market", "role": "MARKETPLACE", "enabled": true,
 "createdAt": "2026-11-03T12:00:00Z", "updatedAt": "2026-11-03T12:00:00Z"}
```

Registering the same code again returns `409` with `"code": "SOURCE_CODE_TAKEN"`.

### Listing events: create, replay, out of order, conflict

The event contract is `ListingEventV1` (`libs/event-contracts`):

```bash
EVENT='{"eventId":"7c0e8b9e-4f51-4d61-9a7e-2f1d3b7f0a11","eventType":"LISTING_UPSERTED","schemaVersion":1,
  "sourceCode":"northwind-supply","externalProductId":"NW-1001","sourceVersion":2,
  "occurredAt":"2026-11-01T10:00:00Z",
  "payload":{"title":"Altora Pro AL-4520X 64GB USB Flash Drive, Black","brand":"Altora","model":"AL-4520X",
             "category":"Electronics > Storage > Flash Drives","gtin":"2000000000008",
             "attributes":{"color":"Black","capacity":"64 GB"}}}'
curl -s -i -X POST localhost:8080/api/v1/listing-events -H 'Content-Type: application/json' -d "$EVENT"
```

```http
HTTP/1.1 201 Created
Location: /api/v1/listings/1
X-Correlation-Id: 3f0c…

{"eventId":"7c0e8b9e-4f51-4d61-9a7e-2f1d3b7f0a11","outcome":"CREATED","listingId":1,"appliedVersion":2,
 "duplicate":false,"reason":"first version of the listing","processedAt":"2026-11-03T12:00:01Z"}
```

| Send next | Response |
|---|---|
| The same body again | `200`, `Idempotent-Replay: true`, `"duplicate": true`, the stored outcome; nothing is written |
| The same `eventId` with a different title | `409 EVENT_ID_REUSED` |
| A new event id with `sourceVersion: 1` (older) | `200`, `"outcome": "STALE_IGNORED"`, `"appliedVersion": null`; version 2 stays |
| A new event id with `sourceVersion: 2` and different content | `409 VERSION_CONFLICT` |
| `"eventType": "LISTING_WITHDRAWN"`, version 3, no payload | `200`, `"outcome": "WITHDRAWN"`; the row becomes a tombstone |
| An upsert with version 2 after that | `200`, `"outcome": "STALE_IGNORED"`: stale events cannot resurrect it |
| An upsert with version 4 | `200`, `"outcome": "REACTIVATED"` |

The exact `reason` strings come from `ListingEventDecider`.

### Asynchronous ingestion

```bash
curl -s -X POST localhost:8080/api/v1/ingestion/events -H 'Content-Type: application/json' \
  -d "{\"events\":[$EVENT]}"
```

```json
{"accepted": 1, "eventIds": ["7c0e8b9e-4f51-4d61-9a7e-2f1d3b7f0a11"], "failed": {},
 "acceptedAt": "2026-11-03T12:00:02Z"}
```

An invalid event anywhere in the batch rejects the whole batch with every violation listed, and nothing is
queued:

```json
{"type": "https://catalogsentinel.io/problems/validation-failed", "title": "Request validation failed",
 "status": 400, "detail": "2 field(s) are invalid", "instance": "/api/v1/ingestion/events",
 "code": "VALIDATION_FAILED", "correlationId": "3f0c…",
 "errors": [{"field": "events[0].sourceVersion", "message": "is required"},
            {"field": "events[0].payload.gtin", "message": "is not a valid GTIN-8/12/13/14 (check digit or length)"}]}
```

(The `type` URI is an identifier, not a web page. The exact messages come from `ListingEventMapper`.)

### Browse and paginate

```bash
curl -s 'localhost:8080/api/v1/listings?sourceCode=acme-market&q=flash%20drive&limit=2'
```

```json
{"items": [{"listingId": 4211, "sourceCode": "acme-market", "externalProductId": "AC-77", "status": "ACTIVE",
            "sourceVersion": 3, "title": "…", "canonicalProductId": 3566, "linkConfidence": 0.97,
            "linkMethod": "RULES_AUTO", "…": "…"}],
 "page": {"limit": 2, "nextCursor": "djF8…", "hasMore": true}}
```

Repeat with `&cursor=djF8…` for the next page.

### Review a match candidate

```bash
curl -s 'localhost:8080/api/v1/match-candidates?reviewStatus=PENDING&limit=1'
curl -s -X POST localhost:8080/api/v1/match-candidates/88/decisions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: review-88-1' -d '{"decision":"APPROVE","reviewer":"alex","reason":"same model and GTIN family"}'
```

A candidate carries `score`, `decision` (`AUTO_MATCH`, `REVIEW`, `NO_MATCH`), `featureScores`
(`model`, `title`, `brand`, `attributes`, `category`, `gtin`), `evidence` (codes such as `MODEL_MATCH`, `GTIN_CONFLICT` or
`VARIANT_CONFLICT` with their effect), `matcherVersion` and both listings side by side. The decision response is
`{"result": {"subjectId", "previousStatus", "newStatus", "changed", "decisionId"}, "subject": {…}}`. With security
enabled the `reviewer` field is ignored and the authenticated key's fingerprint is recorded instead.

### Detect and triage gaps

```bash
curl -s -i -X POST localhost:8080/api/v1/reconciliation/jobs -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: nightly-2026-11-03' -d '{"targetSourceCode":"acme-market","requestedBy":"alex"}'
# 202 Accepted, Location: /api/v1/reconciliation/jobs/7
curl -s localhost:8080/api/v1/reconciliation/jobs/7
curl -s 'localhost:8080/api/v1/gaps?targetSourceId=1&status=OPEN&priority=P1&limit=20'
curl -s -X POST localhost:8080/api/v1/gaps/501/decisions -H 'Content-Type: application/json' \
  -d '{"decision":"CONFIRM","reviewer":"alex","reason":"worth sourcing"}'
```

A job moves `PENDING` → `RUNNING` → `SUCCEEDED`, or back to `PENDING` after a retryable failure
(`attempt`/`maxAttempts`, `nextAttemptAt`, `errorMessage` of the last attempt) and `FAILED` once attempts run
out or the failure is permanent. `leaseExpiresAt` is set while it runs; see the
[runbook](runbook.md#reconciliation-jobs). A finished job reports statistics such as
`{"listingsMatched": 212, "identitiesMerged": 4, "productsEvaluated": 5765, "productsCarried": 4440,
"gapsOpened": 31, "gapsRefreshed": 986, "gapsResolved": 17}`. Each gap carries `reason`, `confidence`,
`priority`, `priorityScore`, the closest candidate, and `evidence` with a human-readable `explanation`, the
reference listings, reference coverage and the **synthetic** demand value with `"source": "SYNTHETIC"`.

### Operations

```bash
curl -s 'localhost:8080/api/v1/admin/dead-letters?queue=catalog-listing-events-dlq&max=5'
curl -s -X POST localhost:8080/api/v1/admin/dead-letters/replay -H 'Content-Type: application/json' \
  -d '{"queue":"catalog-listing-events-dlq","maxMessages":100}'
curl -s localhost:8080/api/v1/admin/outbox
```

Dead-lettered messages show `failureReason` (for example `UNKNOWN_SOURCE: source 'contoso' is not registered`),
`originalMessageId` and `receiveCount`; messages moved by the queue's redrive policy have neither. Fix the cause
before replaying, or the messages return to the DLQ.

## Status codes in one place

| Status | When |
|---|---|
| 200 | Reads; updates; idempotent replays; no-op decisions |
| 201 | A new source, listing or ingestion job, with `Location` |
| 202 | Accepted for asynchronous processing (queued events, reconciliation) |
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `INVALID_CURSOR` |
| 401, 403 | Missing key, missing role (security enabled) |
| 404 | `NOT_FOUND` |
| 409 | `VERSION_CONFLICT`, `EVENT_ID_REUSED`, `SOURCE_CODE_TAKEN`, `INVALID_TRANSITION`, `RECONCILIATION_IN_PROGRESS`, `JOB_KEY_REUSED`, `CANDIDATE_NOT_REVIEWABLE`, `CANDIDATE_STALE` and the other job transition errors |
| 422 | `UNKNOWN_SOURCE`, `SOURCE_DISABLED`, `REFERENCE_SOURCE_DISABLED` |
| 503 | `DEPENDENCY_UNAVAILABLE` (database), or some queued events were not accepted by SQS |
