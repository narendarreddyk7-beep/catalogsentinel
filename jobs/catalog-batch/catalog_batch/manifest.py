"""Batch manifests: schema validation, integrity checks and the deterministic job key.

A manifest describes one export from one source::

    {
      "manifestVersion": 1,
      "sourceCode": "northwind-supply",
      "exportId": "2026-11-03-full",
      "exportedAt": "2026-11-03T00:00:00Z",
      "format": "jsonl",
      "files": [{"path": "listings-00001.jsonl", "sha256": "<hex>", "records": 10000}],
      "syntheticData": true
    }

``files[].path`` is relative to the manifest's location. The job key is derived from the canonical manifest JSON,
so re-submitting the same manifest resumes the same job and any change (a file, a checksum) is a new job.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from typing import Any

import jsonschema

from .validation import SOURCE_CODE, parse_instant

MANIFEST_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["manifestVersion", "sourceCode", "exportId", "exportedAt", "format", "files"],
    "additionalProperties": False,
    "properties": {
        "manifestVersion": {"const": 1},
        "sourceCode": {"type": "string", "pattern": SOURCE_CODE.pattern},
        "exportId": {"type": "string", "minLength": 1, "maxLength": 128},
        "exportedAt": {"type": "string", "minLength": 20},
        "format": {"enum": ["jsonl", "csv"]},
        "syntheticData": {"type": "boolean"},
        "description": {"type": "string", "maxLength": 1000},
        "files": {
            "type": "array", "minItems": 1, "maxItems": 10000,
            "items": {
                "type": "object", "required": ["path", "sha256", "records"], "additionalProperties": False,
                "properties": {
                    "path": {"type": "string", "pattern": r"^[A-Za-z0-9._/-]{1,512}$", "not": {"pattern": r"(^|/)\.\.(/|$)"}},
                    "sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
                    "records": {"type": "integer", "minimum": 0},
                },
            },
        },
    },
}


class ManifestError(ValueError):
    """The manifest is malformed or does not match the files it describes."""


@dataclass(frozen=True)
class Manifest:
    raw: dict[str, Any]
    source_code: str
    export_id: str
    exported_at: datetime
    fmt: str
    files: tuple[dict[str, Any], ...]
    synthetic: bool

    @property
    def declared_records(self) -> int:
        return sum(int(f["records"]) for f in self.files)

    @property
    def job_key(self) -> str:
        return derive_job_key(self.raw)


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def derive_job_key(raw: dict[str, Any]) -> str:
    """``<sourceCode>:<first 24 hex of sha256(canonical manifest)>``; matches the API's job-key pattern."""
    digest = hashlib.sha256(canonical_json(raw).encode("utf-8")).hexdigest()[:24]
    return f"{raw['sourceCode']}:{digest}"


def parse_manifest(text: str) -> Manifest:
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as e:
        raise ManifestError(f"manifest is not valid JSON: {e}") from e
    errors = sorted(jsonschema.Draft202012Validator(MANIFEST_SCHEMA).iter_errors(raw), key=lambda e: list(e.path))
    if errors:
        details = "; ".join(f"{'/'.join(map(str, e.path)) or '(root)'}: {e.message}" for e in errors[:10])
        raise ManifestError(f"manifest does not match schema: {details}")
    exported_at = parse_instant(raw["exportedAt"])
    if exported_at is None:
        raise ManifestError("exportedAt must be an ISO-8601 instant with offset")
    paths = [f["path"] for f in raw["files"]]
    if len(set(paths)) != len(paths):
        raise ManifestError("files[].path must be unique")
    return Manifest(raw=raw, source_code=raw["sourceCode"], export_id=raw["exportId"], exported_at=exported_at,
                    fmt=raw["format"], files=tuple(raw["files"]), synthetic=bool(raw.get("syntheticData", False)))


def sha256_file(path: str, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            block = f.read(chunk)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


def count_records(path: str, fmt: str) -> int:
    """Records in a file: non-blank lines for JSONL; rows after the header for CSV (Spark counts quoted
    multi-line CSV fields as one record, so the CSV count is verified again after parsing)."""
    with open(path, "r", encoding="utf-8") as f:
        if fmt == "jsonl":
            return sum(1 for line in f if line.strip())
        return max(0, sum(1 for line in f if line.strip()) - 1)


def verify_files(manifest: Manifest, local_paths: dict[str, str]) -> None:
    """Checksums and record counts; raises ManifestError listing every mismatch."""
    problems = []
    for f in manifest.files:
        local = local_paths[f["path"]]
        actual = sha256_file(local)
        if actual != f["sha256"]:
            problems.append(f"{f['path']}: sha256 {actual} does not match manifest {f['sha256']}")
            continue
        if manifest.fmt == "jsonl":
            records = count_records(local, manifest.fmt)
            if records != f["records"]:
                problems.append(f"{f['path']}: {records} records, manifest declares {f['records']}")
    if problems:
        raise ManifestError("; ".join(problems))
