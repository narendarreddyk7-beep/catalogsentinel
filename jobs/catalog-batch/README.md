# catalog-batch — manifest-driven batch ingestion (PySpark)

Bulk path for supplier exports. The online API handles single events; this job handles files with
hundreds of thousands of listings and feeds the same idempotent pipeline.

```
manifest.json ─► register job (POST /api/v1/ingestion/jobs, idempotent per manifest)
              ─► verify files (sha256, record counts)
              ─► Spark: parse ─► validate (same rules as the API) ─► normalize (norm-v2 text rules, Python port)
              ─► in-export dedup (identical copies collapse, same-version conflicts rejected,
                 older versions superseded)
              ─► curated Parquet (partitioned by category_root) + rejected JSONL (with reasons)
              ─► publish ListingEventV1 to SQS with metadata.ingestionJobId (per-partition markers)
              ─► PATCH job PUBLISHED; the worker ingests the events; progress = processed_events by job
```

## Guarantees

| Concern | How |
|---|---|
| Re-running the same manifest | Job key = `sourceCode:sha256(canonical manifest)[:24]`; registration returns the existing job; a PUBLISHED job exits immediately |
| Crash mid-publish | Event ids are UUIDv5 of (job key, source, listing, version, type); partitions are a pure function of the event id; completed partitions have markers and are skipped; a partially sent partition is re-sent and the consumer's ledger drops copies |
| Outputs on restart | Overwritten per job (`job=<key>/`), never appended |
| Parity with the online path | `normalize.py` is checked against Java output for ~1,000 fields of the golden catalogs and the shared hand-written cases; content hashes match `ListingContent.contentHash()` |
| Invalid records | Rejected with Java-compatible field names and messages, never published, so the worker does not dead-letter batch events as invalid |
| Delivery | At-least-once (SQS); nothing assumes exactly-once |

## Input format

Manifest (`files[].path` relative to the manifest):

```json
{"manifestVersion": 1, "sourceCode": "northwind-supply", "exportId": "2026-11-03-full",
 "exportedAt": "2026-11-03T00:00:00Z", "format": "jsonl", "syntheticData": true,
 "files": [{"path": "listings-00001.jsonl", "sha256": "<hex>", "records": 10000}]}
```

JSONL record (field names as in the event payload):

```json
{"externalProductId": "NW-0001", "sourceVersion": 3, "updatedAt": "2026-11-02T10:00:00Z",
 "title": "...", "brand": "...", "model": "...", "category": "Kitchen > Storage", "gtin": "...",
 "attributes": {"color": "Gray", "synthetic_demand_index": "0.42"}}
```

`eventType` defaults to `LISTING_UPSERTED`; `LISTING_WITHDRAWN` records carry no content. CSV exports use the
snake_case columns `external_product_id, source_version, event_type, updated_at, title, brand, model, category,
gtin, attributes` (attributes as a JSON object string).

## Running

```bash
pip install -r requirements-lock.txt && pip install -e . --no-deps
python -m catalog_batch.cli run \
  --manifest s3://catalogsentinel-batch/incoming/northwind-supply/2026-11-03/manifest.json \
  --output s3://catalogsentinel-batch/curated/listings \
  --rejected s3://catalogsentinel-batch/rejected \
  --state s3://catalogsentinel-batch/_state \
  --api-url http://localhost:8080
```

Environment: `SQS_ENDPOINT` / `S3_ENDPOINT` for emulators (unset in AWS), `AWS_REGION`,
`CATALOGSENTINEL_API_KEY` when API security is enabled. `--s3-mode native` lets Spark read/write `s3://`
directly on platforms with an S3 connector (EMR, Glue); the default `staged` mode copies through boto3.

## Tests

```bash
pip install -r requirements-lock.txt && pip install -e . --no-deps
pytest
```

The suite starts a local Spark session (`local[2]`) and an in-process moto server (S3 + SQS); the
ingestion-jobs API is replaced by an in-memory fake with the same contract and state machine
(the real service is covered by `IngestionJobServiceIT` in `libs/catalog-core`).
