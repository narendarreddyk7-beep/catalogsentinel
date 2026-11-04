<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli truth/labeled-sample.tsv
     Dataset: scripts/generate-synthetic-data.py --size 10k --seed 7 (synthgen-v1). SYNTHETIC data: robustness to the generator's own perturbations, not real-world accuracy. Seed 7 was used to develop rules-v4; seed 2027 was not. Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: synthetic-10k-seed7/truth/labeled-sample.tsv

- listings: 573, positive pairs: 373, all pairs: 163878
- load+normalize: 344.4 ms, candidate generation: 263.1 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 3160 | 0.9807 | 1.0000 (373/373) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.6223 | 0.7024 | 0.6599 | 262 | 159 | 111 | 629 | 2462.0 |
| title-baseline-v1 | all-pairs | assisted | 0.3343 | 0.9410 | 0.4933 | 351 | 699 | 22 | 629 | 2462.0 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.6223 | 0.7024 | 0.6599 | 262 | 159 | 111 | 629 | 36.1 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3343 | 0.9410 | 0.4933 | 351 | 699 | 22 | 629 | 36.1 |
| rules-v4 | all-pairs | auto-only | 0.9763 | 0.8820 | 0.9268 | 329 | 8 | 44 | 109 | 1645.6 |
| rules-v4 | all-pairs | assisted | 0.8363 | 1.0000 | 0.9109 | 373 | 73 | 0 | 109 | 1645.6 |
| rules-v4 | blocking+matcher | auto-only | 0.9763 | 0.8820 | 0.9268 | 329 | 8 | 44 | 105 | 19.6 |
| rules-v4 | blocking+matcher | assisted | 0.8439 | 1.0000 | 0.9153 | 373 | 69 | 0 | 105 | 19.6 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {hard_negative_same_family=8}
- assisted false positives by kind: {hard_negative_same_family=51, negative_other_family=22}

Auto-match false positives:
- northwind-supply:NW-00004273 ~ contoso-wholesale:CW-00001782 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00004273 ~ acme-market:AM-00003872 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003871 ~ contoso-wholesale:CW-00001782 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003871 ~ acme-market:AM-00003872 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00003254 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00001373 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00002945 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000977 ~ acme-market:AM-00000979 score=0.900 [BRAND_SUBSUMED, MODEL_IN_TITLE, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
