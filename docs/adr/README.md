# Architecture decision records

Each record states the context, the decision, its consequences and the alternatives considered.

| ADR | Decision |
|---|---|
| [0001](0001-modular-monorepo.md) | One repository; pure domain and transactional core libraries; two Spring Boot services sharing one database |
| [0002](0002-at-least-once-idempotent-processing.md) | At-least-once delivery with an idempotency ledger, fingerprints, source-version ordering and row locks |
| [0003](0003-transactional-outbox.md) | Transactional outbox and a `SKIP LOCKED` relay for change events |
| [0004](0004-explainable-rule-based-matching.md) | Explainable rule-based matching with indexed blocking and a held-out evaluation protocol |
| [0005](0005-local-aws-emulation.md) | ElasticMQ and moto instead of AWS for local development and tests |
| [0006](0006-platform-baseline.md) | Java 21, Spring Boot 4.1, Spring JDBC with explicit SQL, Flyway |
| [0007](0007-keyset-pagination.md) | Keyset pagination with opaque, versioned cursors |
| [0008](0008-batch-through-the-event-pipeline.md) | Batch imports publish through the event pipeline with manifest-derived job keys and deterministic event ids |
| [0009](0009-esci-dataset-not-integrated.md) | The ESCI dataset was evaluated and not integrated |
| [0010](0010-synthetic-demand-signal.md) | Gap priority uses a clearly labeled synthetic demand signal |
| [0011](0011-api-key-authentication.md) | Hashed API keys with roles; TLS at the load balancer |
| [0012](0012-dashboard-design.md) | React + TypeScript dashboard on the real API with hand-built charts, served same-origin |
| [0013](0013-match-evidence-currency.md) | Match evidence must be current: supersede stale decisions (audited), re-justify and split products |
| [0014](0014-gaps-follow-product-merges.md) | Selection gaps follow their product through merges, keeping their status and review history |
