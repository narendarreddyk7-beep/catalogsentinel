"""Per-record validation, mirroring the online path so the worker never dead-letters a batch event as invalid.

Rules follow ``ListingEventMapper``, ``IncomingListingEvent`` and ``ListingContent`` in the Java domain:
field names and messages match the Java ``DomainValidationException`` violations.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping, Optional

from . import normalize as n

UPSERTED = "LISTING_UPSERTED"
WITHDRAWN = "LISTING_WITHDRAWN"
MAX_EXTERNAL_ID = 128
MAX_TITLE = 500
MAX_SHORT_FIELD = 200
MAX_ATTRIBUTES = 64
MAX_ATTRIBUTE_KEY = 64
MAX_ATTRIBUTE_VALUE = 500
MAX_FUTURE_SKEW = timedelta(minutes=5)
MAX_LONG = 2 ** 63 - 1
SOURCE_CODE = re.compile(r"^[a-z0-9][a-z0-9_-]{1,63}$")

# CSV column -> record field. JSONL uses the record field names directly (camelCase, like the event payload).
CSV_COLUMNS = {
    "external_product_id": "externalProductId",
    "source_version": "sourceVersion",
    "event_type": "eventType",
    "updated_at": "updatedAt",
    "title": "title",
    "brand": "brand",
    "model": "model",
    "category": "category",
    "gtin": "gtin",
    "attributes": "attributes",
}


@dataclass
class Violation:
    field: str
    message: str


@dataclass
class ValidatedRecord:
    """A record after validation; ``violations`` empty means it can be published."""
    external_product_id: Optional[str]
    source_version: Optional[int]
    event_type: str
    occurred_at: Optional[datetime]
    title: Optional[str] = None
    brand: Optional[str] = None
    model: Optional[str] = None
    category: Optional[str] = None
    gtin: Optional[str] = None
    attributes: dict[str, str] = field(default_factory=dict)
    violations: list[Violation] = field(default_factory=list)

    @property
    def valid(self) -> bool:
        return not self.violations

    def content_hash(self) -> Optional[str]:
        if self.event_type != UPSERTED:
            return None
        return n.content_hash(self.title, self.brand, self.model, self.category, self.gtin, self.attributes)


def is_java_blank(s: str) -> bool:
    return n.java_strip(s) == ""


def _trim_to_none(value: Any) -> Optional[str]:
    if value is None:
        return None
    s = n.java_strip(str(value))
    return s or None


def parse_instant(value: Any) -> Optional[datetime]:
    """ISO-8601 instant with an explicit offset (``Z`` or ``+hh:mm``), like ``java.time.Instant`` parsing."""
    if not isinstance(value, str):
        return None
    s = value.strip()
    if not re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$", s):
        return None
    frac = re.search(r"\.(\d+)", s)
    if frac and len(frac.group(1)) > 6:  # Python datetimes hold microseconds; drop finer digits deterministically
        s = s.replace(frac.group(0), "." + frac.group(1)[:6])
    try:
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None
    return dt.astimezone(timezone.utc)


def format_instant(dt: datetime) -> str:
    """Like ``Instant.toString()``: UTC, ``Z`` suffix, fraction only when non-zero (millis/micros as needed)."""
    dt = dt.astimezone(timezone.utc)
    base = dt.strftime("%Y-%m-%dT%H:%M:%S")
    if dt.microsecond:
        micros = f"{dt.microsecond:06d}"
        frac = micros[:3] if micros.endswith("000") else micros
        return f"{base}.{frac}Z"
    return base + "Z"


def _parse_version(value: Any, violations: list[Violation]) -> Optional[int]:
    if value is None or (isinstance(value, str) and value.strip() == ""):
        violations.append(Violation("sourceVersion", "is required"))
        return None
    version: Optional[int] = None
    if isinstance(value, bool):
        version = None
    elif isinstance(value, int):
        version = value
    elif isinstance(value, float) and math.isfinite(value) and value.is_integer():
        version = int(value)
    elif isinstance(value, str) and re.fullmatch(r"-?\d{1,19}", value.strip()):
        version = int(value.strip())
    if version is None or version > MAX_LONG:
        violations.append(Violation("sourceVersion", "must be an integer"))
        return None
    if version < 0:
        violations.append(Violation("sourceVersion", "must be >= 0"))
    return version


def _parse_attributes(value: Any, violations: list[Violation]) -> dict[str, str]:
    if value is None or value == "":
        return {}
    if isinstance(value, str):  # CSV carries attributes as a JSON object string
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            violations.append(Violation("payload.attributes", "must be a JSON object of string values"))
            return {}
    if not isinstance(value, Mapping):
        violations.append(Violation("payload.attributes", "must be a JSON object of string values"))
        return {}
    cleaned: dict[str, str] = {}
    for raw_key, raw_value in value.items():
        if isinstance(raw_value, (dict, list)):
            violations.append(Violation("payload.attributes", f"value of '{_abbreviate(str(raw_key))}' must be a scalar"))
            continue
        if isinstance(raw_value, bool):
            raw_value = "true" if raw_value else "false"
        key, val = _trim_to_none(raw_key), _trim_to_none(raw_value)
        if key is not None and val is not None:
            cleaned[key] = val
    if len(cleaned) > MAX_ATTRIBUTES:
        violations.append(Violation("payload.attributes", f"must contain at most {MAX_ATTRIBUTES} entries"))
    for key in sorted(cleaned, key=lambda k: k.encode("utf-16-be")):
        if n.java_length(key) > MAX_ATTRIBUTE_KEY:
            violations.append(Violation("payload.attributes",
                                        f"key '{_abbreviate(key)}' exceeds {MAX_ATTRIBUTE_KEY} characters"))
        if n.java_length(cleaned[key]) > MAX_ATTRIBUTE_VALUE:
            violations.append(Violation("payload.attributes." + _abbreviate(key),
                                        f"value exceeds {MAX_ATTRIBUTE_VALUE} characters"))
    return cleaned


def _abbreviate(s: str) -> str:
    return s if len(s) <= 24 else s[:24] + "..."


def _text(field_name: str, value: Optional[str], max_length: int, required: bool,
          violations: list[Violation]) -> None:
    if value is None:
        if required:
            violations.append(Violation(field_name, "must not be blank"))
    elif n.java_length(value) > max_length:
        violations.append(Violation(field_name, f"must be at most {max_length} characters"))


def validate(record: Mapping[str, Any], default_occurred_at: datetime, now: datetime) -> ValidatedRecord:
    """Validates one raw record (already mapped to camelCase field names)."""
    violations: list[Violation] = []

    raw_type = record.get("eventType")
    event_type = UPSERTED if raw_type in (None, "") else raw_type
    if event_type not in (UPSERTED, WITHDRAWN):
        violations.append(Violation("eventType", "must be LISTING_UPSERTED or LISTING_WITHDRAWN"))
        event_type = UPSERTED

    external_id = record.get("externalProductId")
    if external_id is not None and not isinstance(external_id, str):
        external_id = str(external_id)
    if external_id is None or is_java_blank(external_id):
        violations.append(Violation("externalProductId", "must not be blank"))
    elif n.java_length(external_id) > MAX_EXTERNAL_ID:
        violations.append(Violation("externalProductId", f"must be at most {MAX_EXTERNAL_ID} characters"))

    version = _parse_version(record.get("sourceVersion"), violations)

    occurred_at = default_occurred_at
    if record.get("updatedAt") not in (None, ""):
        occurred_at = parse_instant(record.get("updatedAt"))
        if occurred_at is None:
            violations.append(Violation("occurredAt", "updatedAt must be an ISO-8601 instant with offset"))
    if occurred_at is not None and occurred_at > now + MAX_FUTURE_SKEW:
        violations.append(Violation("occurredAt", "is more than 5 minutes in the future"))

    result = ValidatedRecord(external_id, version, event_type, occurred_at, violations=violations)
    if event_type == WITHDRAWN:
        # Tombstones carry no payload; content columns in the export are ignored, not rejected.
        return result

    result.title = _trim_to_none(record.get("title"))
    result.brand = _trim_to_none(record.get("brand"))
    result.model = _trim_to_none(record.get("model"))
    result.category = _trim_to_none(record.get("category"))
    raw_gtin = _trim_to_none(record.get("gtin"))
    if raw_gtin is not None:
        result.gtin = n.parse_gtin(raw_gtin)
        if result.gtin is None:
            violations.append(Violation("payload.gtin", "is not a valid GTIN-8/12/13/14 (check digit or length)"))
    _text("payload.title", result.title, MAX_TITLE, True, violations)
    _text("payload.brand", result.brand, MAX_SHORT_FIELD, False, violations)
    _text("payload.model", result.model, MAX_SHORT_FIELD, False, violations)
    _text("payload.category", result.category, MAX_SHORT_FIELD, False, violations)
    result.attributes = _parse_attributes(record.get("attributes"), violations)
    return result


def from_csv_row(row: Mapping[str, Any]) -> dict[str, Any]:
    """Maps a CSV row (snake_case header) to record field names; unknown columns are ignored."""
    return {target: row.get(column) for column, target in CSV_COLUMNS.items() if column in row}
