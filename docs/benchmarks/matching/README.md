# Matching evaluation history

Every number below was produced by `EvaluationCli` (libs/catalog-domain) on the datasets named, on the
sandbox machine recorded in each report's header. Reports are kept for every matcher version so changes can be
audited; nothing here is estimated or copied from elsewhere.

**Policies.** *auto-only*: only AUTO_MATCH counts as a predicted match (what the system merges without a human).
*assisted*: AUTO_MATCH or REVIEW counts (what a reviewer can confirm from the queue). Scores are pairwise over all
pairs of the set.

## Datasets and how much to trust them

| Dataset | Size | Labels | Bias |
|---|---|---|---|
| Golden dev (`tests/golden-matching/golden-listings.tsv`) | 150 listings, 79 positive pairs | hand-labeled, real brand names used nominatively, synthetic GTINs | used to develop every version |
| Golden holdout (`golden-holdout.tsv`) | 48 listings, 25 positives | hand-labeled before rules-v2 | rules-v3 was motivated by a holdout error, so v3/v4 holdout numbers are optimistic |
| Synthetic seed 7 (`generate-synthetic-data.py --size 10k --seed 7`, labeled sample) | 573 listings, 373 positives | generator truth | used to develop rules-v4; perturbations designed by the same team as the matcher |
| Synthetic seed 2027 | 570 listings, 369 positives | generator truth | unseen samples, but the same generator distribution as seed 7 |

None of these is an estimate of accuracy on a real marketplace catalog.

## Results (all pairs)

| Version | Dataset | auto-only P / R / F1 | assisted P / R / F1 |
|---|---|---|---|
| title-baseline-v1 | golden dev | — | 0.320 / 0.392 / 0.352 |
| rules-v1 | golden dev | 1.000 / 0.127 / 0.225 | 0.818 / 0.684 / 0.745 |
| rules-v2 | golden dev | 1.000 / 0.418 / 0.589 | 0.728 / 0.949 / 0.824 |
| rules-v3 | golden dev | 1.000 / 0.456 / 0.626 | 0.728 / 0.949 / 0.824 |
| rules-v4 | golden dev | 1.000 / 0.456 / 0.626 | 0.735 / 0.949 / 0.829 |
| title-baseline-v1 | golden holdout | — | 0.474 / 0.360 / 0.409 |
| rules-v1 | golden holdout | 1.000 / 0.120 / 0.214 | 0.941 / 0.640 / 0.762 |
| rules-v2 | golden holdout | 0.909 / 0.400 / 0.556 | 0.880 / 0.880 / 0.880 |
| rules-v3 | golden holdout | 1.000 / 0.440 / 0.611 | 0.917 / 0.880 / 0.898 |
| rules-v4 | golden holdout | 1.000 / 0.440 / 0.611 | 0.917 / 0.880 / 0.898 |
| rules-v3 | synthetic seed 7 | 0.916 / 0.882 / 0.899 | 0.736 / 1.000 / 0.848 |
| rules-v4 | synthetic seed 7 | 0.976 / 0.882 / 0.927 | 0.836 / 1.000 / 0.911 |
| rules-v3 | synthetic seed 2027 | 0.920 / 0.875 / 0.897 | 0.759 / 1.000 / 0.863 |
| rules-v4 | synthetic seed 2027 | 0.982 / 0.875 / 0.926 | 0.862 / 1.000 / 0.926 |

Blocking (candidate retrieval) kept 100 % of positive pairs on every dataset while discarding 93–98 % of pairs.

## What changed and why

- **rules-v2**: tier words in marketing text, brand vs. manufacturer/parent, identifier-scheme digit conflicts,
  packaging suffixes, fractions, category taxonomy mismatch (error analysis on golden dev).
- **rules-v3**: token-level model matching and a TIER_FOLLOWS conflict (holdout false positive "K-Mini" vs
  "K-Mini Plus").
- **rules-v4** (with `norm-v2` attribute extraction): voltage (`20V` vs `12V`) and plain-ounce quantities
  (`18 oz` vs `24 oz`) are variant-defining. Found on synthetic seed 7, where rules-v3 auto-matched same-model
  variants differing only in those quantities. No change on the golden sets beyond one fewer review-queue false
  positive.

## Known remaining errors (rules-v4)

- Same-model colour variants whose colours share a family (`Navy` vs `Blue`) are scored as compatible
  (`COLOR_SAME_FAMILY`) rather than conflicting. Deliberate: different sellers often name one colour
  differently; the generator treats them as different products, which is where most remaining synthetic auto
  false positives come from.
- Pack counts stated as a bare number ("4 Food Storage Containers" vs "Set of 8") are not recognised.
- Golden-set misses remain where model numbers use different identifier schemes or a tier word is part of the
  product name.
