# ADR 0001 — One repository, shared domain and core libraries, two deployable services

- Status: accepted
- Date: 2026-11-03

## Context

The platform has an HTTP API, an asynchronous worker, a batch job and a dashboard. The API and the worker must
apply listing events with identical rules (the synchronous and asynchronous paths would otherwise drift), and the
batch job must validate and normalize exactly like the Java code.

## Decision

- One Git repository and one Maven reactor. `libs/catalog-domain` holds pure rules with no framework or I/O;
  `libs/catalog-core` holds transactional services and SQL; `libs/platform-adapters` implements the core's ports
  for AWS, Jackson and Micrometer; `libs/event-contracts` defines the wire format.
- Two Spring Boot applications, `catalog-api` and `ingestion-worker`, that only wire beans and expose transports.
  They share the database; there are no service-to-service HTTP calls.
- The batch job is Python (PySpark) and keeps parity with Java through a shared golden fixture
  (`tests/fixtures/normalization-parity-golden.tsv`) that both test suites check.

## Consequences

- Business rules are tested without Spring, SQS or HTTP; most of the suite runs as plain JUnit or against
  PostgreSQL directly.
- The API and worker scale independently (the worker on queue depth) while sharing one schema. Schema changes
  must stay backward compatible for one release because both may run different versions during a deployment.
- A Python port of the normalizer exists and must be kept in step; the parity fixture fails the build when they
  diverge.

## Alternatives considered

- **Microservices per capability** (ingestion, matching, gaps) with their own databases: more operational surface
  and distributed consistency problems with no benefit at this scale.
- **A single application** that also consumes queues: simpler, but the API's latency would compete with bulk
  consumption, and the two could not scale separately.
