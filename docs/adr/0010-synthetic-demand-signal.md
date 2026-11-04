# ADR 0010 — Gap priority uses a clearly labeled synthetic demand signal

- Status: accepted
- Date: 2026-11-03

## Context

A selection gap matters more when the missing product is in demand. The platform has no access to any real sales,
demand or inventory data, and must not imply that it does.

## Decision

- Priority score = 100 × (0.5 × confidence + 0.3 × demand + 0.2 × reference coverage); P1 at 70 or more, P2 at 50
  or more, otherwise P3.
- *Demand* is `synthetic_demand_index`, a value in [0, 1] that the synthetic data generator draws from a Beta(0.7, 3)
  distribution and ships with reference listings. Without it, demand is 0.5 and labeled unknown.
- Every gap's evidence records the demand value with `"source": "SYNTHETIC"` (or `"UNKNOWN"`), and the dashboard
  labels it "synthetic demand" wherever it appears.

## Consequences

- Priorities are reproducible and demonstrate the mechanism without pretending to know real demand.
- The weights are judgement, not fitted; with real signals (search volume, sales velocity) they should be
  calibrated against outcomes.
- A real demand feed would replace the field without changing the priority code.
