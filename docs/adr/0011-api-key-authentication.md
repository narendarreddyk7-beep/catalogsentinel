# ADR 0011 — Hashed API keys with roles; TLS at the load balancer

- Status: accepted
- Date: 2026-11-03

## Context

A public deployment needs authentication, role-based authorization, HTTPS and extra protection for operator
endpoints. Clients are scripts, the batch job, and a small number of operators using the dashboard. There is no
identity provider in scope.

## Decision

- Stateless `X-Api-Key` authentication, enabled by the `aws` profile. Only SHA-256 hashes of keys are configured
  (from Secrets Manager), compared in constant time; roles `READER`, `REVIEWER`, `INGEST`, `ADMIN`.
- The principal is a key fingerprint and is what review decisions record, so clients cannot spoof the reviewer.
- Operator endpoints need `ADMIN` and are additionally restricted to administrator networks at the load balancer,
  which also blocks `/actuator/*`.
- TLS 1.3/1.2 terminates at the load balancer; tasks run in private subnets reachable only from it.

## Consequences

- Simple to operate and test; no session state, no CSRF surface.
- Keys identify clients, not people, and rotation is manual (two-key overlap procedure in the runbook).
  A real deployment should add SSO in front of the dashboard (for example ALB OIDC authentication) rather than
  hand keys to browsers.

## Alternatives considered

- **OAuth 2.0 / OIDC resource server** (JWTs from Cognito or another IdP): the right choice with real users, but
  an external dependency that cannot run locally without extra infrastructure.
- **Mutual TLS**: strong for service clients, awkward for browsers.
