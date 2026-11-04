# ADR 0002 — At-least-once delivery with idempotent, version-ordered processing

- Status: accepted
- Date: 2026-11-03

## Context

SQS standard queues deliver every message at least once and may reorder them. Producers retry, batch jobs are
re-run after crashes, and operators replay dead letters. The catalog must still end up in the state a single,
ordered application of each event would produce.

## Decision

Make the *effect* of each event happen once instead of trying to make delivery exactly-once:

- **Idempotency ledger**: `processed_events` keyed by `event_id`, written in the same transaction as the listing
  change. A replay finds the row and returns the stored outcome; the primary key settles concurrent deliveries
  (the loser's transaction rolls back and its retry reads the stored outcome).
- **Fingerprints**: a SHA-256 of everything in the event except its id. The same id with a different fingerprint
  is rejected (`EVENT_ID_REUSED`) instead of silently ignored.
- **Source versions, not arrival order**: each event carries the source's monotonically increasing version.
  Older versions are recorded as `STALE_IGNORED`; the same version with different content is a
  `VERSION_CONFLICT`; withdrawals leave tombstones so stale events cannot resurrect a listing.
- **Row locks** (`SELECT … FOR UPDATE`, `INSERT … ON CONFLICT DO NOTHING`) serialize concurrent events for one
  listing at READ COMMITTED.

The consumer bounds retries (5 receives, full-jitter backoff via visibility), dead-letters permanent failures at
once with a reason, pauses on dependency outages, and relies on the queue's redrive policy as the crash safety net.

## Consequences

- Duplicate, reordered, concurrent and replayed deliveries are safe; DLQ replay needs no deduplication tooling.
- The ledger grows with every event and must be retained at least as long as redelivery or replay is possible.
- Producers must supply stable event ids and source versions. Events without a meaningful version are not
  supported.
- Verified by `ListingEventDeciderTest`, `ListingIngestionServiceIT`, `ConcurrentIngestionIT`, `AsyncPipelineIT`
  and the queue consumer tests.

## Alternatives considered

- **SQS FIFO queues** with deduplication: deduplication only covers a 5-minute window and ordering only within a
  message group; throughput limits are lower. It would not remove the need for idempotent processing.
- **Last-write-wins on arrival time**: wrong under reordering.
- **SERIALIZABLE isolation** instead of explicit locks: correct, but contention becomes retryable serialization
  failures and the serialization point is invisible in the code.
