"""Exports built by the end-to-end tooling (scripts/e2e) run through the real batch pipeline, so the outcomes
scripts/smoke.sh and the batch-crash drill expect from the batch container are checked by this suite."""
from __future__ import annotations

import importlib.util
import json
import sys
from datetime import datetime, timezone

import jsonschema

from catalog_batch.pipeline import JobOptions, run_job
from conftest import EVENT_SCHEMA, REPO, FakeJobsApi, drain_queue

NOW = datetime(2026, 11, 3, 12, 0, tzinfo=timezone.utc)


def load(name):
    spec = importlib.util.spec_from_file_location(name, REPO / "scripts" / "e2e" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # dataclasses and `import smoke` resolve through sys.modules
    spec.loader.exec_module(module)
    return module


def load_smoke():
    return load("smoke")


def test_the_smoke_batch_export_publishes_exactly_the_expected_events(spark, aws, tmp_path):
    smoke = load_smoke()
    scenario = smoke.load_scenario("qorvandel")
    manifest = smoke.write_batch_export(scenario, tmp_path / "in")
    source = smoke.source_code(scenario, "batch")
    api = FakeJobsApi(known_sources=(source,))
    options = JobOptions(manifest_uri=str(manifest), output_uri=str(tmp_path / "curated"),
                         rejected_uri=str(tmp_path / "rejected"), state_uri=str(tmp_path / "state"),
                         sqs_endpoint=aws["endpoint"], publish_partitions=2, now=NOW)

    result = run_job(spark, api, options)

    expect = scenario["batch"]["expect"]
    assert result.status == "PUBLISHED"
    assert (result.rows_total, result.rows_valid, result.rows_rejected, result.events_published) == (
        expect["rowsTotal"], expect["rowsValid"], expect["rowsRejected"], expect["eventsPublished"])
    events = [json.loads(m["Body"]) for m in drain_queue(aws["sqs"], aws["queue_url"])]
    assert sorted(e["externalProductId"] for e in events) == ["BAT-CABLE", "BAT-FAN", "BAT-LAMP"]
    for e in events:
        jsonschema.validate(e, EVENT_SCHEMA, format_checker=jsonschema.FormatChecker())
        assert e["sourceCode"] == source and e["metadata"]["ingestionJobId"] == result.job_id
    rejected = spark.read.json(result.rejected_uri)
    assert [r.external_product_id for r in rejected.collect()] == ["BAT-INVALID"]

    again = run_job(spark, api, options)
    assert again.skipped and again.job_id == result.job_id
    assert drain_queue(aws["sqs"], aws["queue_url"]) == []


def test_the_batch_crash_drill_export_crashes_and_resumes_as_the_drill_expects(spark, aws, tmp_path):
    load_smoke()
    drills = load("drills")
    count = 120
    manifest = drills.write_drill_export(tmp_path / "in", "contoso-wholesale", "drill-batch-crash-1", "DRILL-t", count)
    api = FakeJobsApi(known_sources=("contoso-wholesale",))
    base = dict(manifest_uri=str(tmp_path / "in" / "manifest.json"), output_uri=str(tmp_path / "curated"),
                rejected_uri=str(tmp_path / "rejected"), state_uri=str(tmp_path / "state"),
                sqs_endpoint=aws["endpoint"], now=NOW)

    try:
        run_job(spark, api, JobOptions(**base, fail_from_partition=3))
        raise AssertionError("the injected crash did not happen")
    except Exception as e:  # the drill only needs a non-zero exit; the pipeline raises
        assert "injected failure" in str(e)
    job = api.get(1)["job"]
    assert job["status"] == "FAILED" and job["jobKey"] == drills.derive_job_key(manifest)
    before = [json.loads(m["Body"]) for m in drain_queue(aws["sqs"], aws["queue_url"])]

    resumed = run_job(spark, api, JobOptions(**base))

    assert resumed.status == "PUBLISHED" and resumed.attempt == 2 and resumed.job_id == 1
    assert resumed.events_published == count and resumed.partitions_skipped >= 1
    after = [json.loads(m["Body"]) for m in drain_queue(aws["sqs"], aws["queue_url"])]
    ids = [e["eventId"] for e in before + after]
    assert len(ids) == len(set(ids)) == count
