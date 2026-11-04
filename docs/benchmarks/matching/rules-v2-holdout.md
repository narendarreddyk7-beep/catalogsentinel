# Matching evaluation: tests/golden-matching/golden-holdout.tsv

- listings: 48, positive pairs: 25, all pairs: 1128
- load+normalize: 86.3 ms, candidate generation: 51.2 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 74 | 0.9344 | 1.0000 (25/25) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 82.7 |
| title-baseline-v1 | all-pairs | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 82.7 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 3.5 |
| title-baseline-v1 | blocking+matcher | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 3.5 |
| rules-v2 | all-pairs | auto-only | 0.9091 | 0.4000 | 0.5556 | 10 | 1 | 15 | 14 | 109.5 |
| rules-v2 | all-pairs | assisted | 0.8800 | 0.8800 | 0.8800 | 22 | 3 | 3 | 14 | 109.5 |
| rules-v2 | blocking+matcher | auto-only | 0.9091 | 0.4000 | 0.5556 | 10 | 1 | 15 | 14 | 6.7 |
| rules-v2 | blocking+matcher | assisted | 0.8800 | 0.8800 | 0.8800 | 22 | 3 | 3 | 14 | 6.7 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {hard_negative_same_family=1}
- assisted false positives by kind: {hard_negative_same_family=3}

Auto-match false positives:
- keurig-01 ~ keurig-04 score=0.900 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, TIER_WORDS_DIFFER, IDENTIFIER_AGREEMENT]

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- macbook-01 ~ macbook-03 score=0.450 [MODEL_IN_TITLE, VARIANT_CONFLICT]
- macbook-02 ~ macbook-03 score=0.450 [MODEL_IN_TITLE, VARIANT_CONFLICT]
- crayola-02 ~ crayola-03 score=0.650 [BRAND_IN_TITLE, ATTRIBUTES_AGREE]
