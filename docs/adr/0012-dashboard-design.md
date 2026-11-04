# ADR 0012 — Dashboard: React + TypeScript on the real API, hand-built charts, same-origin serving

- Status: accepted
- Date: 2026-11-03

## Context

Operators need six views (overview, catalog explorer, gaps, matching review, batch jobs, system health) that show
real backend state, with loading, empty and error states, and review actions that are safe to retry.

## Decision

- React 19, TypeScript in strict mode, Vite; TanStack Query for server state; React Router; no UI component
  library. Charts (the coverage "planogram", columns, bar lists) are hand-built SVG and HTML with a validated
  colour palette, table views and keyboard focus.
- No demo data in the bundle: every figure comes from the API. Tests use contract-shaped fixtures served by MSW at
  the network layer.
- Review actions send an `Idempotency-Key`; errors show the problem `code` and the correlation id to search for.
- Served by nginx (unprivileged) on the same origin as the API (proxying `/api`), with a strict CSP. When API
  security is on, a key is kept in `sessionStorage` for the tab.

## Consequences

- Small bundle (about 370 kB of JavaScript before compression), no chart-library styling to override, full
  control over accessibility.
- More chart code to maintain than with a library.
- Same-origin serving means the API needs no CORS configuration.

## Alternatives considered

- **A chart library** (Recharts, ECharts): faster to start, but the planogram view is not a standard chart.
- **A component library**: consistent widgets, but a generic look and a large dependency for six screens.
