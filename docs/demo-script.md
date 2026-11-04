# Demo script (about 4½ minutes)

A walkthrough of the local stack for a recorded demo or a live interview. Everything shown is synthetic data
produced by the repository's own generator.

**Before recording** (not shown, takes a few minutes):

```bash
docker compose up -d --build --wait
./scripts/seed-demo.sh 10k        # keep its output on screen for section 2
```

Have three terminal tabs ready (`curl` snippets below), the dashboard at `http://localhost:3000`, and
[docs/architecture.md](architecture.md) open at the system diagram.

---

### 0:00 – 0:30 · The problem and the system

> "CatalogSentinel answers one question for a marketplace: which products do our reference catalogs carry that we
> don't, how sure are we, and how urgent is each one? It's an independent, simulated system: all the data comes
> from a synthetic generator, and the demand signal used for priority is labeled synthetic."

Show the architecture diagram: two Spring Boot services on one PostgreSQL database, SQS between them (ElasticMQ
locally), a PySpark batch job writing through the same queue, and the React dashboard.

### 0:30 – 1:00 · Batch import and the coverage view

Point at the `seed-demo.sh` output: one batch job per source, each line showing the job key, rows, rejected
records and events published.

> "The job key is derived from the manifest's hash, so re-running an import resumes it instead of duplicating it,
> and the event ids are deterministic, so a crashed run can republish safely."

Switch to the dashboard **Overview**: the coverage "planogram", one shelf per category, teal facings carried,
amber facings gaps. Read the headline share and point at "Needs attention".

### 1:00 – 1:50 · Ingestion correctness in four requests

```bash
E='{"eventId":"11111111-1111-4111-8111-111111111111","eventType":"LISTING_UPSERTED","schemaVersion":1,
"sourceCode":"acme-market","externalProductId":"DEMO-1","sourceVersion":2,"occurredAt":"2026-11-01T10:00:00Z",
"payload":{"title":"Altora Pro AL-4520X 64GB USB Flash Drive, Black","brand":"Altora","model":"AL-4520X"}}'
curl -s -i -X POST localhost:8080/api/v1/listing-events -H 'Content-Type: application/json' -d "$E" | head -1   # 201
curl -s -i -X POST localhost:8080/api/v1/listing-events -H 'Content-Type: application/json' -d "$E" | grep -i replay
```

> "The second send is the same event: 200 with `Idempotent-Replay: true`, nothing written. The ledger's primary key
> also settles the case where both copies arrive at the same moment; a test runs ten rounds of eight simultaneous
> deliveries and always gets one write."

Send version 1 with a new event id (`STALE_IGNORED`, the newer version stays) and version 2 with different
content (`409 VERSION_CONFLICT`). Open the listing in **Catalog explorer** to show its event history with each
outcome.

### 1:50 – 2:40 · Matching you can audit

**Product matching** → pending review queue → open the top pair.

> "Candidates come from three indexed lookups, GTIN, brand plus model, and title trigrams, so we never compare
> everything with everything; on the labeled sets that kept every true pair while discarding 93 to 98 percent of
> pairs. Each pair stores its feature scores and evidence, so I can say exactly why it is in review."

Read out the evidence on screen: which features agreed, and which rule or threshold kept it from auto-matching.

Approve or reject it; point at the recorded decision. Mention the measured precision: auto-matches were 100 %
precise on both hand-labeled sets and 98 % on unseen synthetic samples, with recall traded for it.

### 2:40 – 3:30 · Gaps: triage with evidence

**Selection gaps**, sorted by priority. Open a P1 gap.

> "Each gap has a reason, a confidence and a priority. The priority mixes confidence, how many reference catalogs
> carry the product, and the synthetic demand index, which is labeled as synthetic wherever it appears."

Confirm one gap; try to dismiss another without a reason (the form requires one), then dismiss it with a reason.
Show its audit history.

> "Decisions are append-only: database triggers reject updates and deletes on the audit table, and with security
> on, the reviewer is the authenticated key, not a field the client fills in."

**Optional (30 s) · Evidence stays current.** Send a new version of a marketplace listing whose brand and model
change, then open it in the catalog explorer:

```bash
curl -s localhost:8080/api/v1/listings/<id>                    # note canonicalProductId and its candidates
# POST /api/v1/listing-events with sourceVersion + 1 and another brand/model, then:
curl -s localhost:8080/api/v1/listings/<id>                    # a different canonical product
curl -s "localhost:8080/api/v1/match-candidates?listingId=<id>" # the old pair is SUPERSEDED
```

> "A match is a judgement about two listings' content at one moment. When the content changes, the old judgement
> stops counting: the listing leaves the product, the old pair is superseded, a human decision on it gets an audit
> row, and reviewing it now returns 409 instead of linking stale content."

### 3:30 – 4:15 · Failure and recovery

```bash
./scripts/inject-failure.sh poison-message
```

> "A malformed message goes straight to the dead-letter queue with a failure reason, instead of being retried
> forever."

**System health**: dead letters with their reasons, queue depths, outbox backlog, throughput and latency per
minute. Then, if time allows:

```bash
./scripts/inject-failure.sh worker-crash
```

> "This kills the worker with SIGKILL in the middle of 3,000 events. Unacknowledged messages become visible again
> and are redelivered; the script waits until all 3,000 listings exist and checks there is exactly one ledger entry
> per event: anything applied before the crash is recognised as a duplicate instead of being applied twice. It exits
> non-zero if any of that does not hold."

### 4:15 – 4:40 · Close

> "Everything I quoted is measured and recorded in the repository with its method and caveats: a median of about
> 830 events a second on two vCPUs, where the database and the engine saturate both cores; matching limited by title
> trigram retrieval, which I profiled; matching accuracy per rule version. The honest gap is that the Spring Boot
> services were only type-checked in the environment where I built this, so `mvn verify` on a networked machine is
> the first next step."

---

**If something fails live**: `docker compose logs -f ingestion-worker` and `./scripts/smoke.sh` show the state
quickly; the [runbook](runbook.md) has the recovery steps.
