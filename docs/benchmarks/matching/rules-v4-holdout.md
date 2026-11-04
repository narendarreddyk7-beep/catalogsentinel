<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli tests/golden-matching/golden-holdout.tsv
     Golden holdout set (hand-labeled; authored before rules-v2). Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: tests/golden-matching/golden-holdout.tsv

- listings: 48, positive pairs: 25, all pairs: 1128
- load+normalize: 104.8 ms, candidate generation: 55.3 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 74 | 0.9344 | 1.0000 (25/25) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 144.1 |
| title-baseline-v1 | all-pairs | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 144.1 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.0000 | 0.0000 | 0.0000 | 0 | 1 | 25 | 18 | 5.3 |
| title-baseline-v1 | blocking+matcher | assisted | 0.4737 | 0.3600 | 0.4091 | 9 | 10 | 16 | 18 | 5.3 |
| rules-v4 | all-pairs | auto-only | 1.0000 | 0.4400 | 0.6111 | 11 | 0 | 14 | 13 | 88.8 |
| rules-v4 | all-pairs | assisted | 0.9167 | 0.8800 | 0.8980 | 22 | 2 | 3 | 13 | 88.8 |
| rules-v4 | blocking+matcher | auto-only | 1.0000 | 0.4400 | 0.6111 | 11 | 0 | 14 | 13 | 3.6 |
| rules-v4 | blocking+matcher | assisted | 0.9167 | 0.8800 | 0.8980 | 22 | 2 | 3 | 13 | 3.6 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {}
- assisted false positives by kind: {hard_negative_same_family=2}

Auto-match false positives:

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- macbook-01 ~ macbook-03 score=0.450 [MODEL_IN_TITLE, VARIANT_CONFLICT]
- macbook-02 ~ macbook-03 score=0.450 [MODEL_IN_TITLE, VARIANT_CONFLICT]
- crayola-02 ~ crayola-03 score=0.650 [BRAND_IN_TITLE, ATTRIBUTES_AGREE]
