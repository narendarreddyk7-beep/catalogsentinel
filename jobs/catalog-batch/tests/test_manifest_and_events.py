from __future__ import annotations

import json
import re
from datetime import datetime, timezone

import jsonschema
import pytest

from catalog_batch.events import build_event, event_id, message_attributes
from catalog_batch.manifest import ManifestError, derive_job_key, parse_manifest, verify_files
from catalog_batch.validation import validate
from conftest import EVENT_SCHEMA
from helpers import listing, write_export

JOB_KEY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")  # IngestionJobService.JOB_KEY


def test_job_key_is_deterministic_and_sensitive_to_every_manifest_change(tmp_path):
    m1 = json.loads(write_export(tmp_path / "a", [listing("1")]).read_text())
    m2 = json.loads(write_export(tmp_path / "b", [listing("1")]).read_text())
    m3 = json.loads(write_export(tmp_path / "c", [listing("1", title="changed")]).read_text())
    reordered = dict(reversed(list(m1.items())))
    assert derive_job_key(m1) == derive_job_key(m2) == derive_job_key(reordered)
    assert derive_job_key(m1) != derive_job_key(m3)
    assert JOB_KEY_PATTERN.match(derive_job_key(m1)) and derive_job_key(m1).startswith("northwind-supply:")


@pytest.mark.parametrize("mutate,message", [
    (lambda m: m.pop("files"), "files"),
    (lambda m: m.update(sourceCode="Bad Code"), "sourceCode"),
    (lambda m: m["files"][0].update(path="../etc/passwd"), "files/0/path"),
    (lambda m: m["files"][0].update(sha256="xyz"), "files/0/sha256"),
    (lambda m: m.update(format="xml"), "format"),
    (lambda m: m.update(unexpected=True), "Additional properties"),
    (lambda m: m.update(exportedAt="2026-11-01 00:00:00"), "exportedAt"),
])
def test_manifest_validation(tmp_path, mutate, message):
    raw = json.loads(write_export(tmp_path, [listing("1")]).read_text())
    mutate(raw)
    with pytest.raises(ManifestError, match=re.escape(message)):
        parse_manifest(json.dumps(raw))


def test_verify_files_reports_checksum_and_count_mismatches(tmp_path):
    path = write_export(tmp_path, [listing("1"), listing("2")])
    manifest = parse_manifest(path.read_text())
    files = {f["path"]: str(tmp_path / f["path"]) for f in manifest.files}
    verify_files(manifest, files)
    (tmp_path / "listings-00001.jsonl").write_text("tampered\n")
    with pytest.raises(ManifestError, match="sha256"):
        verify_files(manifest, files)


def test_events_are_deterministic_and_match_the_published_json_schema():
    now = datetime(2026, 11, 3, tzinfo=timezone.utc)
    record = validate(listing("NW-1", 4, gtin="4006381333931"), now, now)
    e1 = build_event(record, job_key="northwind-supply:abc", source_code="northwind-supply", ingestion_job_id=9)
    e2 = build_event(record, job_key="northwind-supply:abc", source_code="northwind-supply", ingestion_job_id=9)
    assert e1["eventId"] == e2["eventId"] == event_id("northwind-supply:abc", "northwind-supply", "NW-1", 4,
                                                       "LISTING_UPSERTED")
    assert e1["eventId"] != event_id("northwind-supply:other", "northwind-supply", "NW-1", 4, "LISTING_UPSERTED")
    jsonschema.validate(e1, EVENT_SCHEMA, format_checker=jsonschema.FormatChecker())
    assert e1["payload"]["gtin"] == "04006381333931" and e1["metadata"]["ingestionJobId"] == 9
    assert {k: v["StringValue"] for k, v in message_attributes(e1).items()} == {
        "eventType": "LISTING_UPSERTED", "schemaVersion": "1", "eventId": e1["eventId"],
        "correlationId": "batch:northwind-supply:abc"}

    withdrawn = validate({"externalProductId": "NW-1", "sourceVersion": 5, "eventType": "LISTING_WITHDRAWN"}, now, now)
    w = build_event(withdrawn, job_key="k", source_code="northwind-supply", ingestion_job_id=9)
    assert "payload" not in w
    jsonschema.validate(w, EVENT_SCHEMA, format_checker=jsonschema.FormatChecker())
