<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli truth/labeled-sample.tsv
     Dataset: scripts/generate-synthetic-data.py --size 10k --seed 2027 (synthgen-v1). SYNTHETIC data: robustness to the generator's own perturbations, not real-world accuracy. Seed 7 was used to develop rules-v4; seed 2027 was not. Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: synthetic-10k-seed2027/truth/labeled-sample.tsv

- listings: 570, positive pairs: 369, all pairs: 162165
- load+normalize: 300.1 ms, candidate generation: 181.9 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 3239 | 0.9800 | 1.0000 (369/369) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.5337 | 0.7073 | 0.6084 | 261 | 228 | 108 | 498 | 2224.3 |
| title-baseline-v1 | all-pairs | assisted | 0.3566 | 0.9539 | 0.5192 | 352 | 635 | 17 | 498 | 2224.3 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.5337 | 0.7073 | 0.6084 | 261 | 228 | 108 | 498 | 59.3 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3566 | 0.9539 | 0.5192 | 352 | 635 | 17 | 498 | 59.3 |
| rules-v4 | all-pairs | auto-only | 0.9818 | 0.8753 | 0.9255 | 323 | 6 | 46 | 99 | 1526.1 |
| rules-v4 | all-pairs | assisted | 0.8621 | 1.0000 | 0.9260 | 369 | 59 | 0 | 99 | 1526.1 |
| rules-v4 | blocking+matcher | auto-only | 0.9818 | 0.8753 | 0.9255 | 323 | 6 | 46 | 99 | 22.1 |
| rules-v4 | blocking+matcher | assisted | 0.8621 | 1.0000 | 0.9260 | 369 | 59 | 0 | 99 | 22.1 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {hard_negative_same_family=6}
- assisted false positives by kind: {hard_negative_same_family=40, negative_other_family=19}

Auto-match false positives:
- acme-market:AM-00002097 ~ northwind-supply:NW-00002267 score=0.970 [GTIN_MATCH, BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, NUMBER_MISMATCH]
- acme-market:AM-00002097 ~ contoso-wholesale:CW-00000993 score=0.970 [GTIN_MATCH, BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, NUMBER_MISMATCH]
- acme-market:AM-00000022 ~ acme-market:AM-00000023 score=0.916 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00000043 ~ acme-market:AM-00000046 score=0.927 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00000025 ~ acme-market:AM-00000046 score=0.927 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000045 ~ acme-market:AM-00000046 score=0.909 [BRAND_MATCH, MODEL_IN_TITLE, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
