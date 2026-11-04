# Matching evaluation: tests/golden-matching/golden-listings.tsv

- listings: 150, positive pairs: 79, all pairs: 11175
- load+normalize: 138.6 ms, candidate generation: 126.1 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 284 | 0.9746 | 1.0000 (79/79) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.1905 | 0.0506 | 0.0800 | 4 | 17 | 75 | 76 | 289.2 |
| title-baseline-v1 | all-pairs | assisted | 0.3196 | 0.3924 | 0.3523 | 31 | 66 | 48 | 76 | 289.2 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.1905 | 0.0506 | 0.0800 | 4 | 17 | 75 | 76 | 7.5 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3196 | 0.3924 | 0.3523 | 31 | 66 | 48 | 76 | 7.5 |
| rules-v2 | all-pairs | auto-only | 1.0000 | 0.4177 | 0.5893 | 33 | 0 | 46 | 70 | 213.7 |
| rules-v2 | all-pairs | assisted | 0.7282 | 0.9494 | 0.8242 | 75 | 28 | 4 | 70 | 213.7 |
| rules-v2 | blocking+matcher | auto-only | 1.0000 | 0.4177 | 0.5893 | 33 | 0 | 46 | 70 | 4.6 |
| rules-v2 | blocking+matcher | assisted | 0.7282 | 0.9494 | 0.8242 | 75 | 28 | 4 | 70 | 4.6 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {}
- assisted false positives by kind: {hard_negative_same_family=28}

Auto-match false positives:

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- nike-pegasus-01 ~ nike-pegasus-02 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- nike-pegasus-04 ~ nike-pegasus-05 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- canon-r6-04 ~ canon-r6-05 score=0.486 [BRAND_MATCH, MODEL_DIFFERENT]
- edge-cases-03 ~ edge-cases-04 score=0.400 [GTIN_CONFLICT, BRAND_MATCH, ATTRIBUTES_AGREE]
