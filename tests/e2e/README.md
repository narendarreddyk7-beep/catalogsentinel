# End-to-end tests

| Level | Where | Runs in |
|---|---|---|
| Worker pipeline through real PostgreSQL and ElasticMQ containers (listing event -> ingestion -> outbox -> matching; poison messages; DLQ replay) | `apps/ingestion-worker/src/test/java/.../WorkerPipelineIT.java` | `mvn verify` (CI) |
| API contract through MockMvc and a PostgreSQL container | `apps/catalog-api/src/test/java/.../CatalogApiIT.java` | `mvn verify` (CI) |
| Authorization matrix of the `aws` profile (API keys, roles, public health/docs/metrics scrape) | `apps/catalog-api/src/test/java/.../security/SecuredApiIT.java` | `mvn verify` (CI) |
| Async pipeline with injected database failures (core, in-memory queue) | `libs/catalog-core/src/test/java/.../AsyncPipelineIT.java` | `mvn verify`, sandbox harness |
| Batch job against Spark and an S3/SQS emulator, including crash-and-resume | `jobs/catalog-batch/tests/test_pipeline.py` | `pytest` |
| Whole stack over HTTP, queues, worker and batch container | `scripts/smoke.sh` (`scripts/e2e/smoke.py`, scenario in `scripts/e2e/smoke-scenario.json`, pinned by `SmokeScenarioIT`) | CI `images` job after `docker compose up` |
| Failure drills (database outage, worker kill, poison message, batch crash) | `scripts/inject-failure.sh` | manually, against the compose stack |
