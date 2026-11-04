<!-- Generated: java -cp <catalog-domain classes> io.catalogsentinel.domain.evaluation.EvaluationCli truth/labeled-sample.tsv
     Dataset: scripts/generate-synthetic-data.py --size 10k --seed 2027 (synthgen-v1). SYNTHETIC data: robustness to the
     generator's own perturbations, not real-world accuracy. Machine: 2 vCPU, 7 GiB RAM, openjdk version "21.0.12.1" 2026-08-18. -->
# Matching evaluation: synthetic-10k-seed2027/truth/labeled-sample.tsv

- listings: 570, positive pairs: 369, all pairs: 162165
- load+normalize: 371.3 ms, candidate generation: 254.7 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 3239 | 0.9800 | 1.0000 (369/369) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.5337 | 0.7073 | 0.6084 | 261 | 228 | 108 | 498 | 2304.0 |
| title-baseline-v1 | all-pairs | assisted | 0.3566 | 0.9539 | 0.5192 | 352 | 635 | 17 | 498 | 2304.0 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.5337 | 0.7073 | 0.6084 | 261 | 228 | 108 | 498 | 55.0 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3566 | 0.9539 | 0.5192 | 352 | 635 | 17 | 498 | 55.0 |
| rules-v3 | all-pairs | auto-only | 0.9202 | 0.8753 | 0.8972 | 323 | 28 | 46 | 135 | 1596.1 |
| rules-v3 | all-pairs | assisted | 0.7593 | 1.0000 | 0.8632 | 369 | 117 | 0 | 135 | 1596.1 |
| rules-v3 | blocking+matcher | auto-only | 0.9202 | 0.8753 | 0.8972 | 323 | 28 | 46 | 135 | 19.8 |
| rules-v3 | blocking+matcher | assisted | 0.7593 | 1.0000 | 0.8632 | 369 | 117 | 0 | 135 | 19.8 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {hard_negative_same_family=28}
- assisted false positives by kind: {hard_negative_same_family=96, negative_other_family=21}

Auto-match false positives:
- northwind-supply:NW-00000520 ~ acme-market:AM-00000481 score=0.932 [BRAND_IN_TITLE, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- contoso-wholesale:CW-00000225 ~ acme-market:AM-00000481 score=0.932 [BRAND_IN_TITLE, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000480 ~ acme-market:AM-00000481 score=0.903 [BRAND_IN_TITLE, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00001413 ~ northwind-supply:NW-00001414 score=0.954 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00001413 ~ contoso-wholesale:CW-00000645 score=0.954 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00001413 ~ acme-market:AM-00001289 score=0.937 [BRAND_MATCH, MODEL_IN_TITLE, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001288 ~ acme-market:AM-00001289 score=0.912 [BRAND_MATCH, MODEL_TOKEN_IN_BOTH_TITLES, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00001935 ~ northwind-supply:NW-00001936 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00001935 ~ acme-market:AM-00001779 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001778 ~ northwind-supply:NW-00001936 score=0.930 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00001778 ~ acme-market:AM-00001779 score=0.930 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003073 ~ northwind-supply:NW-00003359 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003073 ~ contoso-wholesale:CW-00001452 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00003073 ~ acme-market:AM-00003074 score=0.930 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00000161 ~ acme-market:AM-00000158 score=0.948 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000157 ~ acme-market:AM-00000158 score=0.930 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000159 ~ northwind-supply:NW-00000164 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000159 ~ contoso-wholesale:CW-00000073 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000159 ~ acme-market:AM-00000160 score=0.947 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00002097 ~ northwind-supply:NW-00002267 score=0.970 [GTIN_MATCH, BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, NUMBER_MISMATCH]
- acme-market:AM-00002097 ~ contoso-wholesale:CW-00000993 score=0.970 [GTIN_MATCH, BRAND_MATCH, MODEL_MATCH, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE, NUMBER_MISMATCH]
- northwind-supply:NW-00003610 ~ acme-market:AM-00003326 score=0.931 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- northwind-supply:NW-00002523 ~ northwind-supply:NW-00002525 score=0.954 [BRAND_MATCH, MODEL_MATCH, TITLE_SIMILAR, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00002331 ~ northwind-supply:NW-00002525 score=0.926 [BRAND_MATCH, MODEL_MATCH, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]
- acme-market:AM-00000022 ~ acme-market:AM-00000023 score=0.916 [BRAND_MATCH, MODEL_IN_TITLE, ATTRIBUTES_AGREE, IDENTIFIER_AGREEMENT]

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
