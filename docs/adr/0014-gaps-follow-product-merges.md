# ADR 0014 — Selection gaps follow their product through identity merges

- Status: accepted
- Date: 2026-11-03
- Related: [ADR 0013](0013-match-evidence-currency.md) (when products merge and split)

## Context

A selection gap belongs to a canonical product and a target marketplace. Products merge whenever current evidence
joins them: an approved match, a new auto-match, or the merge pass before each reconciliation. The merge keeps the
lower product id, so which product survives depends on the order the listings were first seen, which the worker
does not guarantee.

Before this change, reconciliation treated a gap on a merged-away product as obsolete: an `OPEN` or `CONFIRMED` gap
was resolved as `MERGED_PRODUCT`, a `DISMISSED` gap stayed on the dead product, and the surviving product was
evaluated afresh. Consequences, all reproduced by `GapsFollowProductMergesIT` (4 of 4 failed before the change):

- A reviewer's **dismissal stopped suppressing** the product: a new `OPEN` gap appeared for the surviving product.
- A **confirmation was replaced** by a new `OPEN` gap; the confirmed one was closed as `MERGED_PRODUCT`.
- A gap whose product was merged into a product the marketplace **carries** was closed as `MERGED_PRODUCT`
  instead of `AUTO_MATCH_FOUND`, so the same review produced different outcomes depending on event order (found by
  running the smoke scenario with the marketplace's change events first, `SmokeScenarioIT`).

The first two silently undid human decisions, which the review workflow must never do without a recorded policy.

## Decision

At the start of every reconciliation (after matching and the merge pass) and again before invalidated gaps are
resolved, each live gap (`OPEN`, `CONFIRMED`, `DISMISSED`) of the target whose product has status `MERGED` is
followed along `merged_into_id` to the active product at the end of the chain:

1. **The survivor has no live gap for the target**: the gap is **moved** to the survivor
   (`selection_gaps.canonical_product_id` is updated). It keeps its id, status, detection history and review
   decisions. Evaluation then treats it as the survivor's own gap: resolved as `AUTO_MATCH_FOUND` if the target
   carries the survivor, refreshed otherwise; a dismissal keeps suppressing, a confirmation stays confirmed.
2. **The survivor already has a live gap**: the survivor's gap is kept unchanged and the merged product's gap is
   resolved `MERGED_PRODUCT`. Its audit trail gets a `RESOLVE` row by `system:reconciliation` naming the surviving
   gap and stating that its review decisions were not carried over; the run's statistics count it as
   `mergedGapConflicts`, and the log line is a warning.
3. When several merged products compete for one survivor, human-decided gaps (`CONFIRMED`, `DISMISSED`) are
   followed first, then the most recently updated.

A decision is never copied from one gap to another: in case 2 a reviewer sees the survivor's gap and decides it.

## Consequences

- Human decisions survive merges in the common case (only one side had a gap), and outcomes no longer depend on
  the order in which the worker saw the listings.
- In case 2 a reviewer may have to decide the surviving gap again; the conflict is visible in the audit trail,
  the job statistics and the logs rather than hidden.
- Dismissed gaps on merged products are resolved in case 2. This is the one place automation changes a
  human-set gap status, and only for a product that no longer exists, with the reason recorded.
- Moving a gap is not itself a review decision, so it adds no `review_decisions` row; the job statistics count it
  as `gapsFollowedMerge` and the log names the gap and both products.
- Splits (ADR 0013) do not move gaps: the product id that keeps the representative keeps its gaps, and newly
  created products are evaluated on the next run.
