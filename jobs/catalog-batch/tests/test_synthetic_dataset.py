"""The synthetic generator and the batch pipeline agree: only deliberately invalid records are rejected."""
from __future__ import annotations

import csv
import importlib.util
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import pytest

from catalog_batch.manifest import parse_manifest, verify_files
from catalog_batch.pipeline import JobOptions, run_job
from catalog_batch.validation import parse_instant, validate
from conftest import REPO, drain_queue

NOW = datetime(2026, 11, 3, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def dataset(tmp_path_factory):
    spec = importlib.util.spec_from_file_location("synthgen", REPO / "scripts" / "generate-synthetic-data.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    out = tmp_path_factory.mktemp("synthetic")
    assert module.main(["--size", "10k", "--out", str(out), "--seed", "11", "--with-delta"]) == 0
    assert module.main(["--size", "2k", "--out", str(out / "small"), "--seed", "11"]) == 0
    return out


def truth_rows(dataset: Path):
    with open(dataset / "truth" / "listings.tsv", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def test_every_export_is_consistent_and_only_planted_records_are_invalid(dataset):
    planted = {(r["source_code"], r["external_product_id"]) for r in truth_rows(dataset)
               if "invalid_" in r["perturbations"]}
    rejected = set()
    total = 0
    for source_dir in ["northwind-supply", "contoso-wholesale", "acme-market", "acme-market-delta"]:
        manifest = parse_manifest((dataset / source_dir / "manifest.json").read_text())
        assert manifest.synthetic
        verify_files(manifest, {f["path"]: str(dataset / source_dir / f["path"]) for f in manifest.files})
        for f in manifest.files:
            for line in (dataset / source_dir / f["path"]).read_text(encoding="utf-8").splitlines():
                record = json.loads(line)
                total += 1
                if not validate(record, manifest.exported_at, NOW).valid and source_dir != "acme-market-delta":
                    rejected.add((manifest.source_code, record["externalProductId"]))
    assert total > 10_000
    assert rejected == planted and len(planted) > 0


def test_truth_files_are_consistent(dataset):
    summary = json.loads((dataset / "summary.json").read_text())
    rows = truth_rows(dataset)
    assert len(rows) == summary["totalListings"]
    on_target = {r["product_key"] for r in rows if r["source_code"] == "acme-market"}
    with open(dataset / "truth" / "gaps.tsv", encoding="utf-8") as f:
        gaps = list(csv.DictReader(f, delimiter="\t"))
    assert len(gaps) == summary["trueGaps"] > 0
    assert not {g["product_key"] for g in gaps} & on_target
    assert all(0 <= float(g["synthetic_demand_index"]) <= 1 for g in gaps)
    assert 400 < summary["labeledSampleListings"] <= 600


def test_dry_run_validates_the_full_marketplace_export_without_a_job(spark, api, dataset, tmp_path):
    planted = sum(1 for r in truth_rows(dataset)
                  if r["source_code"] == "acme-market" and "invalid_" in r["perturbations"])
    result = run_job(spark, api, JobOptions(
        manifest_uri=str(dataset / "acme-market" / "manifest.json"), output_uri=str(tmp_path / "curated"),
        rejected_uri=str(tmp_path / "rejected"), state_uri=str(tmp_path / "state"), now=NOW, dry_run=True))
    assert result.status == "DRY_RUN" and api.calls == []
    assert result.rows_rejected == planted and result.events_published == result.rows_valid > 3000
    assert spark.read.parquet(result.curated_uri).count() == result.events_published


def test_batch_pipeline_publishes_a_small_marketplace_export(spark, aws, api, dataset, tmp_path):
    small = dataset / "small"
    planted = sum(1 for r in truth_rows(small)
                  if r["source_code"] == "acme-market" and "invalid_" in r["perturbations"])
    result = run_job(spark, api, JobOptions(
        manifest_uri=str(small / "acme-market" / "manifest.json"), output_uri=str(tmp_path / "curated"),
        rejected_uri=str(tmp_path / "rejected"), state_uri=str(tmp_path / "state"), sqs_endpoint=aws["endpoint"],
        publish_partitions=4, now=NOW))
    assert result.rows_rejected == planted
    assert result.events_published == result.rows_valid  # unique external ids, one version each
    assert len(drain_queue(aws["sqs"], aws["queue_url"])) == result.events_published
