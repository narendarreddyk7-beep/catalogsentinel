<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli tests/golden-matching/golden-listings.tsv
     Golden dev set (hand-labeled). Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: tests/golden-matching/golden-listings.tsv

- listings: 150, positive pairs: 79, all pairs: 11175
- load+normalize: 159.2 ms, candidate generation: 87.3 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 284 | 0.9746 | 1.0000 (79/79) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.1905 | 0.0506 | 0.0800 | 4 | 17 | 75 | 76 | 324.2 |
| title-baseline-v1 | all-pairs | assisted | 0.3196 | 0.3924 | 0.3523 | 31 | 66 | 48 | 76 | 324.2 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.1905 | 0.0506 | 0.0800 | 4 | 17 | 75 | 76 | 11.2 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3196 | 0.3924 | 0.3523 | 31 | 66 | 48 | 76 | 11.2 |
| rules-v4 | all-pairs | auto-only | 1.0000 | 0.4557 | 0.6261 | 36 | 0 | 43 | 66 | 240.4 |
| rules-v4 | all-pairs | assisted | 0.7353 | 0.9494 | 0.8287 | 75 | 27 | 4 | 66 | 240.4 |
| rules-v4 | blocking+matcher | auto-only | 1.0000 | 0.4557 | 0.6261 | 36 | 0 | 43 | 66 | 5.3 |
| rules-v4 | blocking+matcher | assisted | 0.7353 | 0.9494 | 0.8287 | 75 | 27 | 4 | 66 | 5.3 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {}
- assisted false positives by kind: {hard_negative_same_family=27}

Auto-match false positives:

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- nike-pegasus-01 ~ nike-pegasus-02 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- nike-pegasus-04 ~ nike-pegasus-05 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- canon-r6-04 ~ canon-r6-05 score=0.486 [BRAND_MATCH, MODEL_DIFFERENT]
- edge-cases-03 ~ edge-cases-04 score=0.400 [GTIN_CONFLICT, BRAND_MATCH, ATTRIBUTES_AGREE]
