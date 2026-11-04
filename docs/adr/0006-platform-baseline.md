# ADR 0006 — Java 21, Spring Boot 4.1, Spring JDBC with explicit SQL, Flyway

- Status: accepted
- Date: 2026-11-03

## Context

The services need transactions with explicit locking, PostgreSQL-specific SQL (`ON CONFLICT`, `SKIP LOCKED`,
partial indexes, `pg_trgm`, JSONB), structured observability and a supported framework baseline.

## Decision

- **Java 21** (LTS) with virtual threads for request handling and queue consumers.
- **Spring Boot 4.1** (current major line) with Spring MVC, Actuator, Micrometer, OpenTelemetry tracing,
  Spring Security for API keys, springdoc for OpenAPI.
- **Spring JDBC with explicit SQL** through `NamedParameterJdbcTemplate`; no JPA.
- **Flyway** migrations, additive only, applied by the API at start-up.
- **AWS SDK for Java v2** for SQS.

## Consequences

- Every statement is visible in the code and reviewable with `EXPLAIN`; locking and conflict handling are
  explicit. More hand-written mapping code than with an ORM.
- The newest Spring Boot line means some APIs moved (for example the health contributor package); this project
  could not compile against the real libraries in the environment that built it, which is the main verification
  gap (see `IMPLEMENTATION_STATUS.md`).

## Alternatives considered

- **JPA/Hibernate**: convenient mapping, but upserts with `ON CONFLICT`, `SKIP LOCKED` claims and partial-index
  invariants would end up as native queries anyway, and implicit flushing obscures transaction boundaries.
- **jOOQ**: type-safe SQL, but adds code generation for little gain at this size.
