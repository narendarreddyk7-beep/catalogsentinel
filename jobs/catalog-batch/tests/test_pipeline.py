"""End-to-end runs of the batch pipeline against local Spark and an S3/SQS emulator (moto server)."""
from __future__ import annotations

import json
from datetime import datetime, timezone

import jsonschema
import pytest

from catalog_batch.manifest import ManifestError
from catalog_batch.pipeline import JobOptions, run_job
from conftest import EVENT_SCHEMA, drain_queue
from helpers import gtin13, listing, write_export

NOW = datetime(2026, 11, 3, 12, 0, tzinfo=timezone.utc)


def mixed_records():
    records = [listing(f"{i:03d}", gtin=gtin13(f"20000000{i:04d}")) for i in range(1, 41)]   # 40 clean listings
    records += [listing("001", 1, gtin=gtin13("200000000001"))]                              # identical duplicate
    records += [listing("002", 2, title="Northwind Widget 002 v2"), listing("002", 3, title="v3 wins")]  # versions
    records += [listing("003", 5, title="variant A"), listing("003", 5, title="variant B")]   # same version, conflict
    records += [{"externalProductId": "004", "sourceVersion": 9, "eventType": "LISTING_WITHDRAWN"}]  # tombstone
    records += [listing("bad-1", gtin="12345"), listing("bad-2", title=""), {"sourceVersion": 1, "title": "no id"},
                "{not json", "[1, 2]"]                                                       # 5 invalid
    return records


def options(tmp_path, aws, **overrides):
    base = dict(manifest_uri=str(tmp_path / "in" / "manifest.json"), output_uri=str(tmp_path / "curated"),
                rejected_uri=str(tmp_path / "rejected"), state_uri=str(tmp_path / "state"),
                sqs_endpoint=aws["endpoint"], publish_partitions=4, now=NOW)
    base.update(overrides)
    return JobOptions(**base)


def bodies(messages):
    return [json.loads(m["Body"]) for m in messages]


def test_full_run_validates_dedups_writes_outputs_and_publishes(spark, aws, api, tmp_path):
    write_export(tmp_path / "in", mixed_records(), files=2)

    result = run_job(spark, api, options(tmp_path, aws))

    # 51 records: 5 invalid + 2 conflicting (003 v5) rejected; 44 valid -> 40 listings published: the 001 duplicate
    # collapses, 002 v1/v2 are superseded by v3, 003 keeps its clean v1, 004 is withdrawn by v9.
    assert (result.rows_total, result.rows_rejected, result.rows_valid, result.events_published) == (51, 7, 44, 40)
    assert result.events_sent_this_run == 40 and result.partitions_skipped == 0
    job = api.get(result.job_id)["job"]
    assert job["status"] == "PUBLISHED" and job["eventsPublished"] == 40

    events = bodies(drain_queue(aws["sqs"], aws["queue_url"]))
    assert len(events) == 40 and len({e["eventId"] for e in events}) == 40
    for e in events:
        jsonschema.validate(e, EVENT_SCHEMA, format_checker=jsonschema.FormatChecker())
        assert e["metadata"]["ingestionJobId"] == result.job_id
    by_id = {e["externalProductId"]: e for e in events}
    assert by_id["002"]["sourceVersion"] == 3 and by_id["002"]["payload"]["title"] == "v3 wins"
    assert by_id["004"]["eventType"] == "LISTING_WITHDRAWN" and "payload" not in by_id["004"]
    assert by_id["003"]["sourceVersion"] == 1  # the conflicting v5 rows were rejected, v1 is the latest valid one

    curated = spark.read.parquet(result.curated_uri)
    assert curated.count() == 40
    row = curated.filter("external_product_id = '010'").first()
    assert row.normalized_brand == "northwind traders" and row.normalized_title == "northwind widget 010 500ml"
    assert row.category_root == "kitchen" and row.normalizer_version == "norm-v2" and row.synthetic_data is True
    assert row.attributes["synthetic_demand_index"] == "0.42"

    rejected = spark.read.json(result.rejected_uri)
    assert rejected.count() == 7
    reasons = {r.field for row in rejected.collect() for r in row.violations}
    assert {"payload.gtin", "payload.title", "externalProductId", "record", "sourceVersion"} <= reasons
    assert any("different content" in k for k in result.rejection_reasons)


def test_rerunning_a_published_manifest_is_a_no_op(spark, aws, api, tmp_path):
    write_export(tmp_path / "in", [listing(f"{i:03d}") for i in range(10)])
    first = run_job(spark, api, options(tmp_path, aws))
    drain_queue(aws["sqs"], aws["queue_url"])

    again = run_job(spark, api, options(tmp_path, aws))

    assert again.skipped and again.job_id == first.job_id and again.events_published == 10
    assert drain_queue(aws["sqs"], aws["queue_url"]) == []


def test_crash_during_publish_is_resumed_without_losing_events(spark, aws, api, tmp_path):
    write_export(tmp_path / "in", [listing(f"{i:03d}") for i in range(60)])

    with pytest.raises(Exception, match="injected failure"):
        run_job(spark, api, options(tmp_path, aws, fail_from_partition=2))
    record = api.get(1)
    assert record["job"]["status"] == "FAILED" and "injected failure" in record["errors"][0]
    sent_before_crash = bodies(drain_queue(aws["sqs"], aws["queue_url"]))
    assert 0 < len(sent_before_crash) < 60

    resumed = run_job(spark, api, options(tmp_path, aws))

    assert resumed.status == "PUBLISHED" and resumed.attempt == 2 and resumed.partitions_skipped == 2
    sent_after = bodies(drain_queue(aws["sqs"], aws["queue_url"]))
    assert resumed.events_sent_this_run == len(sent_after) == 60 - len(sent_before_crash)
    all_ids = [e["eventId"] for e in sent_before_crash + sent_after]
    assert len(all_ids) == len(set(all_ids)) == 60


def test_s3_staged_mode_reads_and_writes_object_storage(spark, aws, api, tmp_path):
    local = tmp_path / "export"
    write_export(local, [listing(f"{i:03d}") for i in range(12)] + [listing("bad", title=" ")])
    for f in local.iterdir():
        aws["s3"].upload_file(str(f), "catalogsentinel-batch", f"incoming/northwind-supply/2026-11-01/{f.name}")

    result = run_job(spark, api, JobOptions(
        manifest_uri="s3://catalogsentinel-batch/incoming/northwind-supply/2026-11-01/manifest.json",
        output_uri="s3://catalogsentinel-batch/curated/listings", rejected_uri="s3://catalogsentinel-batch/rejected",
        state_uri="s3://catalogsentinel-batch/_state", sqs_endpoint=aws["endpoint"], publish_partitions=3, now=NOW,
        work_dir=str(tmp_path / "work")))

    keys = [o["Key"] for o in aws["s3"].list_objects_v2(Bucket="catalogsentinel-batch")["Contents"]]
    safe = "job=" + result.job_key.replace(":", "_")
    assert any(k.startswith(f"curated/listings/{safe}/category_root=kitchen/") and k.endswith(".parquet") for k in keys)
    assert any(k.startswith(f"rejected/{safe}/") and k.endswith(".json") for k in keys)
    assert sum(k.startswith(f"_state/{safe}/published/part-") for k in keys) == 3
    assert (result.rows_total, result.rows_rejected, result.events_published) == (13, 1, 12)
    assert len(drain_queue(aws["sqs"], aws["queue_url"])) == 12
    assert api.get(result.job_id)["job"]["outputUri"] == f"s3://catalogsentinel-batch/curated/listings/{safe}"


def test_csv_exports_are_supported(spark, aws, api, tmp_path):
    rows = [{"external_product_id": "C-1", "source_version": "1", "title": 'Mug, "Large", 12 oz', "brand": "Contoso",
             "category": "Kitchen/Drinkware", "attributes": json.dumps({"color": "Blue"})},
            {"external_product_id": "C-2", "source_version": "x", "title": "Bad version"}]
    write_export(tmp_path / "in", rows, fmt="csv")

    result = run_job(spark, api, options(tmp_path, aws))

    assert (result.rows_total, result.rows_rejected, result.events_published) == (2, 1, 1)
    event = bodies(drain_queue(aws["sqs"], aws["queue_url"]))[0]
    assert event["payload"] == {"title": 'Mug, "Large", 12 oz', "brand": "Contoso", "category": "Kitchen/Drinkware",
                                "attributes": {"color": "Blue"}}


def test_tampered_input_fails_the_job_with_a_reason(spark, aws, api, tmp_path):
    write_export(tmp_path / "in", [listing("1"), listing("2")])
    (tmp_path / "in" / "listings-00001.jsonl").write_text(json.dumps(listing("1")) + "\n")

    with pytest.raises(ManifestError, match="sha256"):
        run_job(spark, api, options(tmp_path, aws))
    record = api.get(1)
    assert record["job"]["status"] == "FAILED" and "sha256" in record["errors"][0]
    assert drain_queue(aws["sqs"], aws["queue_url"]) == []


def test_malformed_manifest_is_rejected_before_any_job_is_registered(spark, aws, api, tmp_path):
    (tmp_path / "in").mkdir()
    (tmp_path / "in" / "manifest.json").write_text('{"manifestVersion": 2}')
    with pytest.raises(ManifestError):
        run_job(spark, api, options(tmp_path, aws))
    assert api.calls == []
