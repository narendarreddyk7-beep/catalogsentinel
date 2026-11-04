# Benchmark run 20261103T203031Z

Commit `1cfa8cb` with uncommitted changes. Sections: env generate quality spark ingest match latency plans http.

## Environment profile

| | |
|---|---|
| CPU | 2 vCPU, Intel(R) Xeon(R) Processor @ 2.10GHz |
| Memory | 8027 MiB |
| Load average at start | 0.43, 0.38, 0.32 |
| OS | Linux 6.18.44-fc-v230 |
| Java | openjdk version "21.0.12.1" 2026-08-18 |
| Python | Python 3.13.18; batch: PySpark 4.2.1 |
| PostgreSQL | PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1) on x86_64-pc-linux-gnu |
| PostgreSQL settings | effective_cache_size=5242888kB, fsync=on, max_connections=100, random_page_cost=4, shared_buffers=163848kB, synchronous_commit=on, work_mem=4096kB |
| Stack at http://localhost:8080 | upnot running |

## Synthetic data generation

| size | wall time | peak RSS of the generator |
|---|---|---|
| 10k | 0.21 s | 34 MiB |
| 100k | 1.89 s | 182 MiB |

## Matching quality and matcher speed (EvaluationCli, in memory)

### tests/golden-matching/golden-listings.tsv

- listings: 150, positive pairs: 79, all pairs: 11175
| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 284 | 0.9746 | 1.0000 (79/79) |
| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| rules-v4 | all-pairs | auto-only | 1.0000 | 0.4557 | 0.6261 | 36 | 0 | 43 | 66 | 237.1 |
| rules-v4 | all-pairs | assisted | 0.7353 | 0.9494 | 0.8287 | 75 | 27 | 4 | 66 | 237.1 |
| rules-v4 | blocking+matcher | auto-only | 1.0000 | 0.4557 | 0.6261 | 36 | 0 | 43 | 66 | 8.1 |
| rules-v4 | blocking+matcher | assisted | 0.7353 | 0.9494 | 0.8287 | 75 | 27 | 4 | 66 | 8.1 |

### tests/golden-matching/golden-holdout.tsv

- listings: 48, positive pairs: 25, all pairs: 1128
| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 74 | 0.9344 | 1.0000 (25/25) |
| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| rules-v4 | all-pairs | auto-only | 1.0000 | 0.4400 | 0.6111 | 11 | 0 | 14 | 13 | 148.5 |
| rules-v4 | all-pairs | assisted | 0.9167 | 0.8800 | 0.8980 | 22 | 2 | 3 | 13 | 148.5 |
| rules-v4 | blocking+matcher | auto-only | 1.0000 | 0.4400 | 0.6111 | 11 | 0 | 14 | 13 | 6.9 |
| rules-v4 | blocking+matcher | assisted | 0.9167 | 0.8800 | 0.8980 | 22 | 2 | 3 | 13 | 6.9 |

### build/bench-10k/truth/labeled-sample.tsv

- listings: 573, positive pairs: 373, all pairs: 163878
| candidate pairs | reduction ratio | positive-pair recall |
|---|---|---|
| 3160 | 0.9807 | 1.0000 (373/373) |
| matcher | scope | policy | precision | recall | F1 | TP | FP | FN | review decisions | time (ms) |
|---|---|---|---|---|---|---|---|---|---|---|
| rules-v4 | all-pairs | auto-only | 0.9763 | 0.8820 | 0.9268 | 329 | 8 | 44 | 109 | 1484.8 |
| rules-v4 | all-pairs | assisted | 0.8363 | 1.0000 | 0.9109 | 373 | 73 | 0 | 109 | 1484.8 |
| rules-v4 | blocking+matcher | auto-only | 0.9763 | 0.8820 | 0.9268 | 329 | 8 | 44 | 105 | 26.1 |
| rules-v4 | blocking+matcher | assisted | 0.8439 | 1.0000 | 0.9153 | 373 | 69 | 0 | 105 | 26.1 |

## Batch pipeline, dry run (Spark stages only, local[*])

| records | rejected | stage seconds |
|---|---|---|
| 42992 | 0 | {"stage_and_verify_inputs": 0.123, "parse_validate_normalize_dedup": 18.288, "write_curated_and_rejected": 5.308, "publish_events": 0.016} |

## Ingestion engine against PostgreSQL (20000 events, two sources, 8 threads, 3 runs)

| run | events | threads | wall time | throughput | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|
| 1 | 20000 events | 8 threads | 26.5 s | 756 events/s | p50 9.08 ms | p95 21.56 ms | p99 33.29 ms |
| 2 | 20000 events | 8 threads | 25.5 s | 783 events/s | p50 8.96 ms | p95 19.94 ms | p99 31.66 ms |
| 3 | 20000 events | 8 threads | 23.5 s | 852 events/s | p50 8.23 ms | p95 18.81 ms | p99 30.13 ms |

Correctness after each run:

    | check | listings=20000 processed_events=20000 outbox=20000 |
    | redelivery | 2000 replayed | 2000 recognised as duplicates | 4856 events/s |
    | check | listings=20000 processed_events=20000 outbox=20000 |
    | redelivery | 2000 replayed | 2000 recognised as duplicates | 4948 events/s |
    | check | listings=20000 processed_events=20000 outbox=20000 |
    | redelivery | 2000 replayed | 2000 recognised as duplicates | 5095 events/s |

## DB-backed matching (3000 listings matched against 20000)

| match | 3000 listings | 124.6 s | 24 listings/s | candidates=54797 canonical=2996 |

## Event-to-link latency in process, and one reconciliation (5000 listings, all matched)

| match | 5000 listings | 87.4 s | 57 listings/s | candidates=37862 canonical=3323 |
| event-to-link (in-process, sequential) | 500 events | 55 events/s | p50 17.94 ms | p95 26.82 ms | p99 29.98 ms | max 33.96 ms |
| reconcile | acme-market | SUCCEEDED | 1.7 s | stats {"gapsOpened": 1033, "listingsMatched": 0, "productsCarried": 1701, "identitiesMerged": 18, "productsEvaluated": 2734} |

Event-to-link is one event applied and matched on the calling thread, as the worker's two consumers do;
it excludes SQS, the outbox relay and consumer polling, which only the http section measures.

## Query plans for the statements added by the repair pass

Full plans: `build/benchmarks/repair-run-1/plans.txt`. Execution times:

- 1. MatchCandidateRepository.linkableFor: current, linkable candidates of one listing: 0.257 ms
- 2. MatchCandidateRepository.supersedeStaleFor after the busy listing changed (rolled back): 2.901 ms
- 3. CanonicalProductRepository.findMergeablePairs (merge pass before every reconciliation): 77.458 ms
- 4. GapRepository.referenceProducts: one keyset batch of 200 with the eligibility predicate: 1.076 ms
- 5. GapRepository.bestCandidate: closest current candidate in the target for one product: 0.146 ms
- 6. GapRepository.invalidatedGaps: live gaps whose product no longer qualifies: 5.604 ms
- 7. GapRepository.liveGapsOnMergedProducts with 500 products merged away (rolled back): 2.695 ms
- 8. ReconciliationJobRepository.claim among 20,000 finished jobs and 1 due job (rolled back): 0.320 ms

## Through the stack: HTTP latency and event-to-catalog latency

_SKIPPED: the stack is not running at http://localhost:8080 (docker compose up -d --build --wait)_

Written to build/benchmarks/repair-run-1/report.md

---

Note added when copying this report into docs/evidence: two cosmetic errors in the environment profile of this run were fixed in scripts/benchmark.sh afterwards: "upnot running" means the stack was not running, and `shared_buffers=163848kB` is the raw setting 16384 (pages of 8 kB, i.e. 128MB) with its unit appended. The corrected profile is `15b-benchmark-env-profile-rerun.md`. The measured sections are unaffected. The raw output of each section is in `15c-benchmark-raw-section-output.txt`.
