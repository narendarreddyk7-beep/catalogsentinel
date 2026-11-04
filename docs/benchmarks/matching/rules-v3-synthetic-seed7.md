<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli truth/labeled-sample.tsv
     Dataset: scripts/generate-synthetic-data.py --size 10k --seed 7 (synthgen-v1). SYNTHETIC data: robustness to the
     generator's own perturbations, not real-world accuracy. Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: synthetic-10k-seed7/truth/labeled-sample.tsv

- listings: 573, positive pairs: 373, all pairs: 163878
- load+normalize: 319.2 ms, candidate generation: 237.4 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 3160 | 0.9807 | 1.0000 (373/373) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.6223 | 0.7024 | 0.6599 | 262 | 159 | 111 | 629 | 2102.6 |
| title-baseline-v1 | all-pairs | assisted | 0.3343 | 0.9410 | 0.4933 | 351 | 699 | 22 | 629 | 2102.6 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.6223 | 0.7024 | 0.6599 | 262 | 159 | 111 | 629 | 54.3 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3343 | 0.9410 | 0.4933 | 351 | 699 | 22 | 629 | 54.3 |
| rules-v3 | all-pairs | auto-only | 0.9164 | 0.8820 | 0.8989 | 329 | 30 | 44 | 148 | 1609.4 |
| rules-v3 | all-pairs | assisted | 0.7357 | 1.0000 | 0.8477 | 373 | 134 | 0 | 148 | 1609.4 |
| rules-v3 | blocking+matcher | auto-only | 0.9164 | 0.8820 | 0.8989 | 329 | 30 | 44 | 144 | 30.9 |
| rules-v3 | blocking+matcher | assisted | 0.7416 | 1.0000 | 0.8516 | 373 | 130 | 0 | 144 | 30.9 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {hard_negative_same_family=30}
- assisted false positives by kind: {hard_negative_same_family=111, negative_other_family=23}

Auto-match false positives:
- acme-market:AM-00002749 ~ northwind-supply:NW-00003052 score=0.955 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00003656 ~ acme-market:AM-00003321 score=0.900 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001582 ~ northwind-supply:NW-00001747 score=0.931 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00004273 ~ contoso-wholesale:CW-00001782 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00004273 ~ acme-market:AM-00003872 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003871 ~ contoso-wholesale:CW-00001782 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003871 ~ acme-market:AM-00003872 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00000346 ~ acme-market:AM-00000724 score=0.955 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00000793 ~ acme-market:AM-00000724 score=0.955 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000417 ~ northwind-supply:NW-00000447 score=0.946 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000417 ~ acme-market:AM-00000418 score=0.946 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00003520 ~ acme-market:AM-00003191 score=0.915 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00001479 ~ acme-market:AM-00003191 score=0.915 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003190 ~ northwind-supply:NW-00003521 score=0.933 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003190 ~ acme-market:AM-00003191 score=0.904 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00003254 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00001373 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00002945 ~ acme-market:AM-00002946 score=0.900 [BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00002946 ~ northwind-supply:NW-00003256 score=0.900 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001076 ~ acme-market:AM-00001078 score=0.948 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000977 ~ acme-market:AM-00000979 score=0.900 [BRAND_SUBSUMED, MODEL_IN_TITLE, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000486 ~ northwind-supply:NW-00000530 score=0.900 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000486 ~ contoso-wholesale:CW-00000229 score=0.900 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00000574 ~ acme-market:AM-00001213 score=0.933 [BRAND_SUBSUMED, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001212 ~ acme-market:AM-00001213 score=0.900 [BRAND_SUBSUMED, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
