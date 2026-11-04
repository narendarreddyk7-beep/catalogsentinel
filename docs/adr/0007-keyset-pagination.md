# ADR 0007 — Keyset pagination with opaque, versioned cursors

- Status: accepted
- Date: 2026-11-03

## Context

Every collection endpoint (listings, candidates, gaps, jobs, sources) must page through tables that grow
continuously and are written while being read.

## Decision

Paginate by keyset: each list has a total order ending in its primary key, backed by an index in that order. The
response's `nextCursor` encodes the sort key of the last row (`v1|…`, Base64URL); the next request continues
strictly after it. `limit` is capped at 200; one extra row is fetched to compute `hasMore`.

## Consequences

- Constant cost per page regardless of depth, and no skipped or repeated rows when rows are inserted between
  requests (`ListingQueryIT` inserts rows after the first page and checks that every original row is returned
  exactly once, in order).
- No "jump to page N" and no total count; the dashboard shows "load more" and separate aggregate figures.
- Cursors are tied to a sort order; changing an endpoint's order needs a new cursor version, which the `v1`
  prefix allows.

## Alternatives considered

- **OFFSET/LIMIT**: cost grows with depth and pages shift under concurrent inserts.
