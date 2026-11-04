# Security

CatalogSentinel is a portfolio system that processes synthetic data only. This document describes the controls
built into the code and the Terraform, what was verified, and the risks knowingly accepted.

## Threat model in brief

| Asset | Threat | Main controls |
|---|---|---|
| Catalog state (listings, links, gaps) | Unauthorized writes; forged or replayed events | API keys with roles; idempotency ledger with fingerprints; version ordering |
| Review audit trail | Repudiation, tampering | Reviewer taken from the authenticated principal; append-only table enforced by triggers |
| Operator functions (DLQ replay, outbox requeue) | Abuse to re-inject or read raw messages | `ADMIN` role and a network allow-list at the load balancer |
| Credentials (database, API keys) | Leakage through the repository, images, Terraform state or logs | Secrets Manager; only key hashes configured; nothing secret in the repository |
| Data in transit and at rest | Interception, disclosure | TLS at the load balancer, TLS to RDS and SQS; encryption at rest for RDS, SQS, S3 |
| Availability | Poison messages, dependency outages, retry storms | Bounded retries, DLQs, outage pause, transaction and connection timeouts |

## Authentication

- **Local development** (`CATALOGSENTINEL_SECURITY_ENABLED=false`, the default): **no authentication** — every
  request is permitted, health details are shown, and review decisions record the `reviewer` field from the
  request prefixed with `local:`. The compose stack therefore binds every published port (API, worker, dashboard,
  PostgreSQL, ElasticMQ, the S3 emulator, Prometheus, Jaeger) to `127.0.0.1`; `BIND_ADDRESS` changes that for a
  trusted network only. Never expose a local-mode stack.
- **`aws` profile**: stateless API-key authentication. Clients send `X-Api-Key: <key>`.
  - The service is configured only with **SHA-256 hashes** of keys, as comma-separated entries
    `<64-hex-sha256>:<ROLE>[|<ROLE>]` in `CATALOGSENTINEL_API_KEYS`. A configuration value that is not a 64-hex
    hash is rejected at start-up, so plaintext keys cannot be configured by mistake.
  - The presented key is hashed and compared in constant time (`MessageDigest.isEqual`) against every configured
    hash.
  - The principal name is `api-key:<first 12 hex digits of the hash>`, a non-reversible fingerprint. It is what the
    audit trail records as the reviewer; any `reviewer` field in the request is ignored, so the trail cannot be
    spoofed by a client.
  - No sessions, no cookies: CSRF protection is disabled because there is nothing for a cross-site request to ride on.

Generate a key and its configuration entry:

```bash
key=$(openssl rand -base64 32)
echo "give this to the client: $key"
echo "configure:  $(printf '%s' "$key" | sha256sum | cut -d' ' -f1):REVIEWER"
```

## Authorization

Rules from `SecurityConfiguration` (first match wins); `SecuredApiIT` pins them down end to end:

| Request | Allowed |
|---|---|
| `GET /actuator/health`, `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**` | everyone (health: status only; component details for `ADMIN`, see below) |
| `GET /actuator/prometheus` | everyone in the application; unreachable from outside (see below) |
| Any other `/actuator/**` | `ADMIN` |
| `/api/v1/admin/**` (any method) | `ADMIN` |
| `POST /api/v1/listing-events`, `POST` and `PATCH /api/v1/ingestion/**` | `INGEST`, `ADMIN` |
| `POST /api/v1/match-candidates/*/decisions`, `POST /api/v1/gaps/*/decisions` | `REVIEWER`, `ADMIN` |
| `POST /api/v1/sources`, `PATCH /api/v1/sources/**`, `POST /api/v1/reconciliation/jobs` | `ADMIN` |
| `GET /api/v1/**` | `READER`, `REVIEWER`, `INGEST`, `ADMIN` |
| Anything else | denied |

Failures are problem-detail responses: `401 UNAUTHENTICATED` (no or unknown key) and `403 FORBIDDEN` (missing
role).

### Health endpoint

Probes need only the status, so health is public but its component details (database, disk space) are not:
`management.endpoint.health.show-details` defaults to `when-authorized` with role `ADMIN` (`HEALTH_SHOW_DETAILS`).
Anonymous callers and `READER`, `REVIEWER` and `INGEST` keys get `{"status": "UP"}`; `ADMIN` keys get the
components (`SecuredApiIT.healthDetailsAreShownOnlyToAdminKeys`). The local compose stack sets
`HEALTH_SHOW_DETAILS=always` because it has no authentication; with security enabled the API refuses to start
with `always` (`HealthDetailsPolicy`, `HealthDetailsPolicyTest`), so the local setting cannot leak into a secured
deployment. On AWS the load balancer additionally answers `/actuator/*` with 403.

## Administrative endpoints

The DLQ and outbox endpoints can read raw message bodies and change delivery state. They are protected twice:

1. The application requires `ADMIN` for every method under `/api/v1/admin/**`.
2. The load balancer forwards `/api/v1/admin/*` only from `admin_cidrs` and answers everything else under
   `/api/v1/admin/*` and `/actuator/*` with a fixed 403, so actuator endpoints are never reachable from outside.

The Prometheus endpoint is deliberately unauthenticated inside the application: the ADOT collector runs as a
sidecar in the same task and scrapes it over localhost without a key. It stays private because the load balancer
never forwards `/actuator/*` and the task security group admits traffic only from the load balancer. Metrics
carry no secrets.

## Transport security

- The load balancer listens on 443 with the `ELBSecurityPolicy-TLS13-1-2-2021-06` policy (TLS 1.3 and 1.2) and
  a certificate you provide (`certificate_arn`); port 80 only redirects to HTTPS. It drops invalid header fields.
- Behind it, tasks run in private subnets and receive plain HTTP only from the load balancer's security group
  (checkov `CKV_AWS_378` accepted, below).
- The database connection uses `sslmode=require`, and the RDS parameter group sets `rds.force_ssl = 1`.
- The SQS queue policies deny any request without `aws:SecureTransport`.

## Secrets and configuration

- **No credentials in the repository.** `.env.example` and the compose file contain local-development defaults
  for the local emulators and database only. `.gitignore` excludes `.env`, `*.tfvars` (except examples), state
  files, keys and certificates.
- **Database**: RDS generates and rotates the master password itself (`manage_master_user_password`), storing it in
  Secrets Manager. ECS injects username and password into the containers from that secret at start-up; the value
  never appears in Terraform variables or task definitions.
- **API keys**: Terraform creates an empty Secrets Manager secret; operators set its value out of band
  (`aws secretsmanager put-secret-value`, see [deployment.md](deployment.md)), so the hashes never enter Terraform
  state. Only the task execution role can read either secret.
- **Configuration** comes from environment variables with local defaults (`application.yml`), so the same image
  runs locally and on AWS.
- **CI/CD**: the deploy workflow authenticates to AWS with GitHub OIDC, so there are no long-lived AWS keys in
  GitHub; the job runs only after an environment reviewer approves it.

## Least-privilege IAM

| Role | Can | Cannot |
|---|---|---|
| Task execution | Pull images, write the service's logs, read the two task secrets | Anything at runtime |
| `catalog-api` task | Send to the listing queue; read attributes of all queues; receive, delete and change visibility on the DLQs; send to the source queues (replay); write EMF metrics and traces | Consume work queues, touch S3, read secrets |
| `ingestion-worker` task | Consume both work queues; send to the change queue and to the DLQs; write EMF metrics and traces | Read or delete DLQ messages, touch S3 |
| `dashboard` task | Nothing | Everything |
| Batch job (`catalog-batch` policy) | Read `incoming/*`; read, write and delete `curated/*`, `rejected/*`, `_state/*`; list only those prefixes; send to the listing queue | Read other prefixes, consume queues, call any other service |

The only wildcard resource is X-Ray's `PutTraceSegments`/`PutTelemetryRecords`, which do not support
resource-level permissions. Trust policies are restricted with `aws:SourceAccount`.

## Data protection

- RDS storage is encrypted; SQS uses SSE; the S3 bucket uses SSE-S3, versioning, lifecycle expiry and a full
  public-access block with ACLs disabled (bucket-owner enforced).
- RDS has deletion protection on by default; ECR repositories use immutable tags and scan images on push.
- VPC flow logs are enabled.

## Application security

- **Injection**: every SQL statement uses bound parameters (`NamedParameterJdbcTemplate`); identifiers are never
  built from input. The text search uses `ILIKE` with escaped wildcards and a trigram index.
- **Input validation**: every event and request is validated with all violations reported at once; batches are
  capped at 500 events, page sizes at 200, DLQ peeks at 10 and replays at 1,000 messages; raw message bodies
  shown to operators are truncated to 4,000 characters.
- **Containers**: the Spring Boot images run as a non-root system user; the API and worker tasks use a read-only
  root filesystem with a writable `/tmp` volume; the dashboard runs on `nginx-unprivileged`.
- **Dashboard**: served same-origin, so the API needs no CORS. nginx sends a strict Content-Security-Policy
  (`default-src 'self'`, no inline scripts, `frame-ancestors 'none'`), `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY` and `Referrer-Policy: no-referrer`. When security is on, a user pastes an API key that is
  kept in `sessionStorage` for that tab only.
- **Dependencies**: versions are pinned exactly (Maven, npm lockfile, Python lock file, container tags); CI runs
  checkov on the Terraform.

## Verification

| Control | Evidence |
|---|---|
| Key parsing, hashing, plaintext rejection | `ApiKeyAuthenticationFilterTest`, **executed** (2/2) |
| Authorization matrix, public probes and scrape, health details per role (anonymous, READER, REVIEWER, INGEST, ADMIN) | `SecuredApiIT`, type-checked, **not executed** here (needs Maven Central and Docker) |
| Public health details refused when security is on | `HealthDetailsPolicyTest` |
| Local ports bound to 127.0.0.1 | `docker compose config` (host_ip 127.0.0.1 for every published port) |
| Append-only audit trail | `GapReconciliationIT.auditTrailIsAppendOnlyAtTheDatabaseLevel`, executed against PostgreSQL 16 |
| Terraform controls | checkov: 247 passed, 0 failed, 16 skipped with reasons (`infra/terraform/.checkov.yaml`) |

## Accepted risks (checkov skips)

Each entry fails when its skip is removed; all are cost or scope decisions for a demo environment.

| Check | Accepted because | Change for production |
|---|---|---|
| `CKV_AWS_158`, `CKV_AWS_136`, `CKV_AWS_145`, `CKV_AWS_149`, `CKV_AWS_354` | AWS-managed keys instead of customer-managed KMS keys (no per-key charges) | Use CMKs with key policies |
| `CKV_AWS_144`, `CKV2_AWS_62`, `CKV_AWS_18` | Single region; no replication, event notifications or S3 access-log bucket | Add as required |
| `CKV2_AWS_57` | The API-key secret is rotated by operators, the database secret by RDS | Automate key rotation |
| `CKV2_AWS_28` | No WAF on the load balancer (billable) | Add AWS WAF with rate-based rules |
| `CKV_AWS_336` | The dashboard's nginx renders its config at start-up | Pre-render config, then use a read-only root filesystem |
| `CKV_AWS_157`, `CKV_AWS_118` | Single-AZ RDS (`multi_az` is a variable) and no enhanced monitoring | Enable both |
| `CKV_AWS_338` | 30-day log retention (a variable) | 365 days or policy |
| `CKV_AWS_394` | Availability zones are taken in order from the data source | Pin zone IDs |
| `CKV_AWS_378` | HTTP between load balancer and tasks inside the VPC | End-to-end TLS if policy requires it |

## Known gaps

- API keys identify clients, not people: there is no SSO, no per-user accounts and no self-service key rotation.
  A real deployment should put an identity provider in front of the dashboard (for example ALB OIDC
  authentication) instead of handing API keys to browsers.
- No rate limiting in the application; it would come from WAF rate rules.
- The security configuration has not been run against the real Spring Security 7 libraries in this environment;
  `SecuredApiIT` is the first thing to run on a networked machine.
