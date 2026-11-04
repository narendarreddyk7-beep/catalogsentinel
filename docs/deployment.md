# Deployment

Two targets: the **local stack**, which needs only Docker and is what the project is built and demonstrated on,
and **AWS**, described by Terraform that this project has never applied. Nothing in the repository creates AWS
resources automatically; every AWS step below is something you run deliberately and pay for.

## Local stack

**Requirements**: Docker with Compose v2.20 or newer (the root `compose.yaml` uses `include`), about 4 GiB of
memory for the containers, free ports 3000, 5000, 5432, 8080, 8081, 9324 (and 9090, 16686 for observability).

```bash
cp .env.example .env              # optional: local overrides; defaults work as they are
docker compose up -d --build --wait
./scripts/seed-demo.sh 10k        # synthetic catalog: generate, stage in S3 (moto), batch job per source, reconcile
open http://localhost:3000        # dashboard
./scripts/smoke.sh                # end-to-end checks: HTTP, queues, worker, batch container
```

| Service | Port | Notes |
|---|---|---|
| `postgres` | 5432 | PostgreSQL 16, volume `pgdata`, `random_page_cost=1.1` |
| `elasticmq` | 9324 | SQS-compatible; queues and DLQs from `infra/compose/elasticmq.conf` (redrive at 6 receives) |
| `moto` | 5000 | S3-compatible, for the batch job |
| `catalog-api` | 8080 | Runs the Flyway migrations at start-up; security disabled locally |
| `ingestion-worker` | 8081 | Consumers and outbox relay |
| `dashboard` | 3000 | nginx serving the built React app, proxying `/api` to the API |
| `batch` (profile `batch`) | — | The PySpark job image, run on demand by `seed-demo.sh` and `inject-failure.sh` |
| `prometheus`, `jaeger` (profile `observability`) | 9090, 16686 | `docker compose --profile observability up -d`; set `TRACING_SAMPLING_PROBABILITY=1.0` to export spans |

Useful variations:

```bash
./scripts/seed-demo.sh 100k                      # larger synthetic catalog (several minutes)
./scripts/inject-failure.sh worker-crash         # failure drills: db-outage | worker-crash | poison-message | batch-crash
./scripts/benchmark.sh                           # reproduces docs/benchmarking.md sections that can run here
docker compose down -v                           # stop and delete all local data
```

Running a service outside Docker (for debugging) needs Java 21 and Maven:
`mvn -pl apps/catalog-api -am spring-boot:run` with the environment variables from `.env.example`, pointing at the
compose PostgreSQL and ElasticMQ.

## AWS

### What the Terraform creates

`infra/terraform/environments/demo` composes six modules:

| Module | Resources |
|---|---|
| `network` | VPC over two availability zones, public and private subnets, internet gateway, one NAT gateway, S3 gateway endpoint, VPC flow logs |
| `messaging` | SQS queues `catalog-listing-events` and `catalog-change-events` with DLQs (redrive at 6 receives), SSE, TLS-only queue policies |
| `storage` | S3 batch bucket: SSE-S3, versioning, lifecycle expiry, public access blocked, ACLs disabled |
| `database` | RDS PostgreSQL 16 (gp3, encrypted, master password managed by RDS in Secrets Manager, `rds.force_ssl`, Performance Insights, 7-day backups, deletion protection) |
| `service` | ECR repositories, ECS cluster, Fargate ARM64 services for the API, worker and dashboard (with ADOT sidecars), HTTPS load balancer with admin and actuator rules, task roles, API-key secret, worker auto-scaling on queue depth |
| `observability` | SNS alarm topic (optional e-mail subscriptions) and CloudWatch alarms: DLQ not empty, oldest message age, API 5xx and p95 latency, database CPU and storage, outbox age |

The environment also defines a least-privilege IAM policy for running the batch job (output `batch_policy_arn`),
to attach to whatever runs it: an EMR Serverless application, an ECS task or a CI runner.

### Cost

These resources are billed while they exist, most of them hourly whether or not anyone uses the system: the NAT
gateway, the load balancer, the RDS instance, the Fargate tasks (API, worker and dashboard, each with a sidecar
except the dashboard), CloudWatch logs, metrics and alarms, Secrets Manager secrets, and smaller amounts for SQS
requests, S3 and ECR storage, X-Ray traces and flow logs. Estimate with the
[AWS Pricing Calculator](https://calculator.aws/) for your region before applying, and destroy the stack when the
demo is over. Production-grade options that cost more (Multi-AZ RDS, WAF, customer-managed KMS keys) are off; see
[security.md](security.md#accepted-risks-checkov-skips).

### Prerequisites

1. An AWS account and permissions to create the resources above.
2. Remote state: an S3 bucket (versioned, encrypted) and a DynamoDB table with partition key `LockID` (string) for
   state locking.
3. An ACM certificate, DNS-validated, for the hostname you will point at the load balancer.
4. For the deploy workflow: a GitHub OIDC identity provider in the account and a deploy role whose trust policy
   allows only this repository and the `demo` environment (`token.actions.githubusercontent.com:sub` =
   `repo:<owner>/<repo>:environment:demo`), with permissions for Terraform, ECR push and ECS updates.
5. Terraform 1.9.x and the AWS CLI, if you run anything from your machine.

### First deployment

```bash
cd infra/terraform/environments/demo
cp terraform.tfvars.example terraform.tfvars        # git-ignored; region, certificate, CIDRs, bucket name
terraform init -backend-config="bucket=<state-bucket>" -backend-config="key=catalogsentinel/demo.tfstate" \
  -backend-config="region=<region>" -backend-config="dynamodb_table=<lock-table>" -backend-config="encrypt=true"

# 1. The image registries must exist before the first image push.
terraform apply -target=module.service.aws_ecr_repository.app

# 2. Build and push the images (the deploy workflow does this; by hand:)
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
tag=$(git rev-parse --short=12 HEAD)
docker build -f apps/Dockerfile.spring --build-arg APP=catalog-api --build-arg PORT=8080 -t <registry>/catalogsentinel/catalog-api:$tag .
# … the same for ingestion-worker (PORT=8081) and the dashboard (apps/dashboard); push all three.
# Fargate tasks run on ARM64: use docker buildx build --platform linux/arm64 --push (the Dockerfiles build their
# jars and assets on the native platform, so only the small runtime stage is emulated).

# 3. Review the full plan, then apply it.
terraform plan -var image_tag=$tag -out=tfplan
terraform apply tfplan

# 4. Configure API keys out of band so they never enter Terraform state (see docs/security.md).
aws secretsmanager put-secret-value --secret-id "$(terraform output -raw api_keys_secret_arn)" \
  --secret-string '<sha256-of-admin-key>:ADMIN,<sha256-of-reviewer-key>:REVIEWER'
aws ecs update-service --cluster catalogsentinel-demo --service catalog-api --force-new-deployment
```

Then point a DNS record for the certificate's hostname at the load balancer (`terraform output url` shows its
DNS name) and verify:

```bash
API=https://<your-hostname> API_KEY=<admin-key> SMOKE_BATCH=skip ./scripts/smoke.sh
```

`SMOKE_BATCH=skip` because the batch section drives the local compose stack's batch container and S3 emulator;
the smoke test reports the skipped section. Run the batch job against the deployment's bucket separately
([batch README](../jobs/catalog-batch/README.md#running)).

The API applies the Flyway migrations when it starts; several tasks starting together are safe because Flyway
takes a database lock.

### Subsequent deployments: the `deploy` workflow

`.github/workflows/deploy.yml` never runs on push. Start it manually (**Actions → deploy → Run workflow**), choose
the environment and whether to apply. It then:

1. waits for a required reviewer of the GitHub `demo` environment to approve the run;
2. assumes the deploy role through OIDC (no stored AWS keys);
3. checks that the ECR repositories exist, then builds the three images for `linux/arm64` with buildx and pushes
   them tagged with the commit;
4. runs `terraform init` with the remote state settings and `terraform plan`;
5. applies the saved plan only if the run was started with **apply** checked.

Configure these **variables** on the `demo` environment (none of them is secret): `AWS_REGION`,
`DEPLOY_ROLE_ARN`, `TF_STATE_BUCKET`, `TF_LOCK_TABLE`, `CERTIFICATE_ARN`, `BATCH_BUCKET_NAME`, and the JSON lists
`ALLOWED_INGRESS_CIDRS`, `ADMIN_CIDRS`, `ALARM_EMAILS`. A plan-only run is the safe way to review changes before
an apply.

### Running the batch job on AWS

Stage an export and its manifest under `s3://<batch-bucket>/incoming/<source>/<export>/`, then run the
`catalog-batch` image (or `python -m catalog_batch.cli run …`) with the `batch_policy_arn` permissions and:

```bash
export CATALOGSENTINEL_API_URL=https://<your-hostname> CATALOGSENTINEL_API_KEY=<an INGEST key> AWS_REGION=<region>
python -m catalog_batch.cli run --queue demo-catalog-listing-events \
  --manifest s3://<batch-bucket>/incoming/<source>/<export>/manifest.json \
  --output s3://<batch-bucket>/curated/listings --rejected s3://<batch-bucket>/rejected --state s3://<batch-bucket>/_state
```

Queue names on AWS carry the environment prefix (`demo-`). See
[jobs/catalog-batch/README.md](../jobs/catalog-batch/README.md) for the other options.

### Rollback

Run the workflow with an earlier commit (images are immutable and tagged by commit), or update the ECS services
to the previous task definition revision. Migrations are additive, so the previous application version runs
against the newer schema.

### Tear-down

Deletion protection is on for the database and the load balancer. To remove everything:

```bash
terraform apply -var deletion_protection=false -var image_tag=<current-tag>   # takes a final DB snapshot otherwise
terraform destroy -var deletion_protection=false -var image_tag=<current-tag>
```

With `deletion_protection = true` the database takes a final snapshot (`<name>-db-final`) when destroyed; that
snapshot is billed until you delete it. Empty the batch bucket and the ECR repositories first if Terraform refuses
to delete them.

## What CI checks on every push

`ci.yml`: Maven build and tests (with Testcontainers), the batch job's pytest suite, dashboard tests and
production build, `terraform fmt -check`, `terraform init -backend=false` and `validate`, checkov, `docker compose
config`, the four container images, and a smoke test of the compose stack whose OpenAPI document is saved as a
build artifact. None of it touches AWS.
