# ADR 0003 — Transactional outbox for change events

- Status: accepted
- Date: 2026-11-03

## Context

When a listing changes, matching must re-run for it. Writing the listing and then sending a message to SQS is a
dual write: a crash between the two loses the event, and sending first can announce a change that then rolls
back.

## Decision

Write a `ListingChangedV1` row to `outbox_events` in the same transaction as the change. A relay in the worker
polls due rows every 250 ms, claims a batch with `FOR UPDATE SKIP LOCKED`, sends it to the change queue, and marks
the rows `PUBLISHED` in the claiming transaction. Failed rows back off exponentially (1 s to 5 min) and become
`FAILED` after 10 attempts, which is alerted on and can be requeued through the admin API. Published rows are
purged after 7 days.

## Consequences

- No lost and no phantom change events. Delivery downstream is at least once (a commit failure after SQS accepted
  the batch republishes it), which the idempotent matching consumer absorbs.
- Several relay instances can run without publishing a row in overlapping runs.
- The claim transaction stays open during the SQS call; batches are small and SQS calls have timeouts, which
  bounds lock time. Lease columns would avoid that at the cost of more writes and lease-expiry logic.
- Change events are delayed by up to one poll interval plus the send time.

## Alternatives considered

- **Change data capture** (logical replication to a stream): no polling, but an extra component (Debezium or
  DMS) to run and operate locally and on AWS.
- **Publishing after commit** from the application: loses events on crashes.
