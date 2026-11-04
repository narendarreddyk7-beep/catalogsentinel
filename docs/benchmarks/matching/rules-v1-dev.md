# Matching evaluation: /home/claude/catalogsentinel/tests/golden-matching/golden-listings.tsv

- listings: 150, positive pairs: 79, all pairs: 11175
- load+normalize: 137.7 ms, candidate generation: 65.9 ms

## Candidate retrieval (blocking)

| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 284 | 0.9746 | 1.0000 (79/79) |

## Matchers

| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| title-baseline-v1 | all-pairs | auto-only | 0.1500 | 0.0380 | 0.0606 | 3 | 17 | 76 | 72 | 291.0 |
| title-baseline-v1 | all-pairs | assisted | 0.3152 | 0.3671 | 0.3392 | 29 | 63 | 50 | 72 | 291.0 |
| title-baseline-v1 | blocking+matcher | auto-only | 0.1500 | 0.0380 | 0.0606 | 3 | 17 | 76 | 72 | 5.3 |
| title-baseline-v1 | blocking+matcher | assisted | 0.3152 | 0.3671 | 0.3392 | 29 | 63 | 50 | 72 | 5.3 |
| rules-v1 | all-pairs | auto-only | 1.0000 | 0.1266 | 0.2247 | 10 | 0 | 69 | 56 | 162.6 |
| rules-v1 | all-pairs | assisted | 0.8182 | 0.6835 | 0.7448 | 54 | 12 | 25 | 56 | 162.6 |
| rules-v1 | blocking+matcher | auto-only | 1.0000 | 0.1266 | 0.2247 | 10 | 0 | 69 | 56 | 3.1 |
| rules-v1 | blocking+matcher | assisted | 0.8182 | 0.6835 | 0.7448 | 54 | 12 | 25 | 56 | 3.1 |

## Rule-based matcher: error analysis (all pairs)

- auto-only false positives by kind: {}
- assisted false positives by kind: {hard_negative_same_family=12}

Auto-match false positives:

True matches not surfaced (neither AUTO_MATCH nor REVIEW):
- sony-wh1000x-02 ~ sony-wh1000x-03 score=0.677 [BRAND_IN_TITLE, ATTRIBUTES_AGREE]
- samsung-q80c-01 ~ samsung-q80c-02 score=0.450 [BRAND_MATCH, MODEL_SIMILAR, ATTRIBUTES_AGREE, TIER_MISMATCH]
- samsung-q80c-02 ~ samsung-q80c-03 score=0.450 [BRAND_IN_TITLE, MODEL_SIMILAR, ATTRIBUTES_AGREE, TIER_MISMATCH]
- airpods-01 ~ airpods-03 score=0.681 [MODEL_VARIANT_SUFFIX, ATTRIBUTES_AGREE]
- airpods-02 ~ airpods-03 score=0.646 [MODEL_VARIANT_SUFFIX, ATTRIBUTES_AGREE]
- echo-dot-05 ~ echo-dot-06 score=0.685 [BRAND_MATCH, ATTRIBUTES_AGREE]
- dewalt-drill-01 ~ dewalt-drill-02 score=0.597 [BRAND_MATCH, MODEL_MATCH, NUMBER_MISMATCH]
- dewalt-drill-02 ~ dewalt-drill-03 score=0.442 [BRAND_MATCH, MODEL_IN_TITLE, NUMBER_MISMATCH]
- dewalt-drill-04 ~ dewalt-drill-05 score=0.450 [BRAND_MATCH, MODEL_MATCH, TIER_MISMATCH]
- duracell-02 ~ duracell-03 score=0.678 [BRAND_IN_TITLE, ATTRIBUTES_AGREE]
- sandisk-microsd-01 ~ sandisk-microsd-02 score=0.350 [BRAND_MATCH, MODEL_CONFLICT, ATTRIBUTES_AGREE]
- nike-pegasus-01 ~ nike-pegasus-02 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- nike-pegasus-04 ~ nike-pegasus-05 score=0.450 [BRAND_MATCH, ATTRIBUTES_AGREE, TIER_MISMATCH]
- fitbit-03 ~ fitbit-04 score=0.450 [BRAND_MATCH, MODEL_VARIANT_SUFFIX, VARIANT_CONFLICT]
- canon-r6-01 ~ canon-r6-02 score=0.350 [BRAND_MATCH, MODEL_CONFLICT, ATTRIBUTES_AGREE]
- canon-r6-04 ~ canon-r6-05 score=0.350 [BRAND_MATCH, MODEL_CONFLICT]
- tplink-01 ~ tplink-02 score=0.622 [BRAND_MATCH, MODEL_DIFFERENT]
- controllers-04 ~ controllers-05 score=0.250 [BRAND_MISMATCH, ATTRIBUTES_AGREE]
- purina-01 ~ purina-02 score=0.250 [BRAND_MISMATCH, TITLE_SIMILAR]
- tide-01 ~ tide-02 score=0.250 [BRAND_MISMATCH, ATTRIBUTES_AGREE]
- galaxy-s24-01 ~ galaxy-s24-02 score=0.350 [BRAND_MATCH, MODEL_CONFLICT, ATTRIBUTES_AGREE]
- nespresso-01 ~ nespresso-02 score=0.250 [BRAND_MISMATCH, MODEL_CONFLICT, COLOR_SAME_FAMILY, ATTRIBUTES_AGREE]
- ninja-blender-01 ~ ninja-blender-02 score=0.250 [BRAND_MISMATCH, MODEL_MATCH, ATTRIBUTES_AGREE]
- levis-01 ~ levis-02 score=0.250 [BRAND_MISMATCH]
- edge-cases-03 ~ edge-cases-04 score=0.400 [GTIN_CONFLICT, BRAND_MATCH, ATTRIBUTES_AGREE]
