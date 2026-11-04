# ADR 0005 — ElasticMQ for SQS and moto for S3 in local development and tests

- Status: accepted
- Date: 2026-11-03

## Context

The full system must run with `docker compose up` and no AWS account, and the tests must exercise real SQS and S3
API calls rather than mocks of the SDK.

## Decision

- **SQS**: ElasticMQ (`softwaremill/elasticmq-native`), configured with the same queues, DLQs and redrive policy as
  the Terraform. The worker's integration test runs against it through Testcontainers.
- **S3** (batch job only): moto in server mode, locally and in the batch tests (which also use it for SQS).
- Services select the emulator with an endpoint override (`SQS_ENDPOINT`, `S3_ENDPOINT`); without it, the AWS SDK's
  default endpoints and credential chain apply. Emulator credentials are dummy values in `.env.example`.

## Consequences

- Real AWS SDK calls in development and tests, no account needed, no charges.
- Emulators differ from AWS in limits and timing: queue-depth attributes are approximate in both, ElasticMQ's
  receive-count semantics are close but not identical, and moto's throughput says nothing about S3 or SQS.
  Benchmarks therefore never report emulator throughput as a platform result.
- LocalStack would cover both services in one container; two small, purpose-built emulators were enough here
  and keep the local stack light.
