# ADR 0004 — Explainable rule-based matching with indexed blocking and a held-out evaluation protocol

- Status: accepted
- Date: 2026-11-03

## Context

Listings from different suppliers must be resolved to one canonical product. Mistakes are asymmetric: a false
merge hides a real selection gap and corrupts every downstream count, while a missed match only adds review work.
Reviewers must be able to see why a pair was matched. There is no large labeled corpus of real listings.

## Decision

- **Normalize** text, units, brands, models and categories, and extract variant-defining attributes.
- **Block** with three indexed retrievals (exact GTIN, normalized brand + model prefix, top-10 title trigram via
  `pg_trgm`) instead of comparing all pairs.
- **Score** with a weighted average of interpretable features, then apply explicit precedence rules for
  conflicting identifiers and variants. Store every feature score and evidence code with the candidate.
- **Decide** with two thresholds (auto-match at 0.90, review at 0.70); only `AUTO_MATCH` and human approvals merge
  identities.
- **Evaluate** every version on a hand-labeled development set, a holdout set labeled before it was used, and
  synthetic sets from the generator, reporting auto-only and assisted precision, recall and F1, blocking recall and
  reduction, with each set's bias stated. Quality floors are enforced by a test.

## Consequences

- Every decision is reproducible and explainable from stored evidence; the matcher is deterministic and
  symmetric (tested over the whole golden set).
- Measured: blocking kept 100 % of true pairs while discarding 93–98 % of pairs; `rules-v4` auto-match precision
  1.0 on both golden sets and 0.982 on unseen synthetic samples (from 0.920 for `rules-v3`), with recall traded
  away deliberately (auto-only recall 0.44–0.46 on the golden sets, review queue picks up the rest).
- The rules encode the author's knowledge of product data; the labeled sets are small and partly synthetic, so
  accuracy on real catalogs is unknown. The holdout numbers for later versions are optimistic (one holdout error
  motivated a rule).

## Alternatives considered

- **Learned matchers** (gradient-boosted features, transformer cross-encoders): likely better recall with enough
  labels, but no realistic labeled data was available, and explanations would be post hoc.
- **Title similarity alone**: measured as the baseline, assisted F1 0.352 on the development set.
