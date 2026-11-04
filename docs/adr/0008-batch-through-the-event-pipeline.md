# ADR 0008 — Batch imports publish events through the same pipeline, with manifest-derived job keys

- Status: accepted
- Date: 2026-11-03

## Context

Bulk exports (hundreds of thousands of records) must be validated, normalized, kept as curated data and loaded
into the catalog, restartably and without duplicating effects, and with the same rules as real-time ingestion.

## Decision

- Spark validates, normalizes (Java-parity port), deduplicates within the export, and writes curated Parquet
  partitioned by top-level category plus rejected records with reasons.
- Loading goes **through the listing-event queue**, not directly into the database: one event per surviving
  record, applied by the same worker code as every other event.
- The job key is `sourceCode:sha256(canonical manifest)`; registration is idempotent, so re-submitting a manifest
  resumes its job. A `FAILED` job restarts as attempt + 1; a `PUBLISHED` job is never redone.
- Event ids are UUIDv5 values derived from the job key, source, external id, version and event type, so a
  re-run produces the same ids. Publishing is partitioned by `crc32(eventId) mod N` with a completion marker per
  partition; a restart skips finished partitions.
- Job progress is derived from the ledger (`processed_events.ingestion_job_id`), not counted on the job row.

## Consequences

- One set of ingestion rules, one idempotency mechanism, one place to monitor; the ledger absorbs overlaps after a
  crash.
- Bulk loads are limited by worker throughput rather than by a bulk `COPY`; for the sizes measured here that was
  acceptable, and the worker scales out on queue depth.
- The Python normalizer must stay identical to the Java one (parity fixture in both test suites).

## Alternatives considered

- **Bulk `COPY` into staging tables and set-based merge**: faster, but a second implementation of the
  versioning, tombstone and outbox rules.
- **Job progress counters on the job row**: a hot row under concurrent workers.
