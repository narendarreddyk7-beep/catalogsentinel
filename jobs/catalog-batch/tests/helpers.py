"""Builders for test exports."""
from __future__ import annotations

import csv
import hashlib
import io
import json
from pathlib import Path
from typing import Any, Iterable


def gtin13(body12: str) -> str:
    """Appends the GS1 check digit to 12 digits."""
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body12))
    return body12 + str((10 - total % 10) % 10)


def write_export(directory: Path, records: Iterable[dict[str, Any]] | Iterable[str], *, source: str = "northwind-supply",
                 fmt: str = "jsonl", export_id: str = "test-export-1", files: int = 1,
                 exported_at: str = "2026-11-01T00:00:00Z") -> Path:
    """Writes records (dicts, or raw lines for malformed input) into ``files`` files plus a manifest."""
    directory.mkdir(parents=True, exist_ok=True)
    rows = list(records)
    chunks = [rows[i::files] for i in range(files)]
    entries = []
    for i, chunk in enumerate(chunks, start=1):
        name = f"listings-{i:05d}.{fmt}"
        if fmt == "jsonl":
            text = "".join((r if isinstance(r, str) else json.dumps(r)) + "\n" for r in chunk)
        else:
            buf = io.StringIO()
            columns = ["external_product_id", "source_version", "event_type", "updated_at", "title", "brand", "model",
                       "category", "gtin", "attributes"]
            writer = csv.DictWriter(buf, fieldnames=columns, lineterminator="\n")
            writer.writeheader()
            for r in chunk:
                writer.writerow({c: r.get(c, "") for c in columns})
            text = buf.getvalue()
        (directory / name).write_text(text, encoding="utf-8")
        entries.append({"path": name, "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                        "records": len(chunk)})
    manifest = {"manifestVersion": 1, "sourceCode": source, "exportId": export_id, "exportedAt": exported_at,
                "format": fmt, "files": entries, "syntheticData": True}
    path = directory / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return path


def listing(external_id: str, version: int = 1, title: str | None = None, **extra: Any) -> dict[str, Any]:
    record = {"externalProductId": external_id, "sourceVersion": version,
              "title": title if title is not None else f"Northwind Widget {external_id} 500 ml",
              "brand": "Northwind Traders Inc.", "model": f"NW-{external_id}", "category": "Kitchen > Storage",
              "attributes": {"color": "Gray", "synthetic_demand_index": "0.42"}}
    record.update(extra)
    return record
