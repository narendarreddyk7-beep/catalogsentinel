# Matching evaluation: tests/golden-matching/golden-holdout.tsv

- listings: 48, positive pairs: 25, all pairs: 1128
- load+normalize: 73.6 ms, candidate generation: 36.3 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 73 | 0.9353 | 1.0000 (25/25) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 93.5 |
| title-baseline-v1 | all-pairs | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 93.5 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 13.8 |
| title-baseline-v1 | blocking+matcher | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 13.8 |
| rules-v1 | all-pairs | auto-only | 1.0000 | 0.1200 | 0.2143 | 3 | 0 | 22 | 14 | 72.7 |
| rules-v1 | all-pairs | assisted | 0.9412 | 0.6400 | 0.7619 | 16 | 1 | 9 | 14 | 72.7 |
| rules-v1 | blocking+matcher | auto-only | 1.0000 | 0.1200 | 0.2143 | 3 | 0 | 22 | 14 | 6.1 |
| rules-v1 | blocking+matcher | assisted | 0.9412 | 0.6400 | 0.7619 | 16 | 1 | 9 | 14 | 6.1 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {}
- assisted false positives by kind: {hard_negative_same_family=1}

Auto-match false positives:

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- macbook-01 ~ macbook-03 score=0.450 [VARIANT_CONFLICT]
- macbook-02 ~ macbook-03 score=0.450 [VARIANT_CONFLICT]
- bose-soundlink-01 ~ bose-soundlink-02 score=0.697 [BRAND_MATCH, ATTRIBUTES_AGREE]
- keurig-01 ~ keurig-02 score=0.250 [BRAND_MISMATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, TIER_MISMATCH]
- sonicare-01 ~ sonicare-02 score=0.250 [BRAND_MISMATCH, MODEL_MATCH, ATTRIBUTES_AGREE]
- sonicare-03 ~ sonicare-04 score=0.250 [BRAND_MISMATCH, ATTRIBUTES_AGREE]
- crayola-02 ~ crayola-03 score=0.624 [BRAND_IN_TITLE, ATTRIBUTES_AGREE]
- crayola-04 ~ crayola-05 score=0.678 [BRAND_MATCH, ATTRIBUTES_AGREE]
- coke-03 ~ coke-04 score=0.250 [BRAND_MISMATCH, ATTRIBUTES_AGREE]
