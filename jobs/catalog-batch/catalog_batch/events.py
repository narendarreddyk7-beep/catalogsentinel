"""Listing events (contract ``listing-event.v1``) with deterministic ids for idempotent restarts."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from .validation import UPSERTED, ValidatedRecord, format_instant

# Fixed namespace: the same (job, source, listing, version, type) always yields the same eventId, so a restarted
# job re-sends identical events and the consumer's idempotency ledger discards the copies.
EVENT_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, "https://catalogsentinel.io/ns/batch-listing-events")
PRODUCER = "catalog-batch/0.1.0"
SCHEMA_VERSION = 1


def event_id(job_key: str, source_code: str, external_product_id: str, source_version: int, event_type: str) -> str:
    name = "\u001f".join([job_key, source_code, external_product_id, str(source_version), event_type])
    return str(uuid.uuid5(EVENT_NAMESPACE, name))


def build_event(record: ValidatedRecord, *, job_key: str, source_code: str, ingestion_job_id: int,
                accepted_at: Optional[datetime] = None) -> dict[str, Any]:
    """The event body; ``metadata.acceptedAt`` is transport metadata and is excluded from the fingerprint."""
    assert record.valid and record.external_product_id is not None and record.source_version is not None
    event: dict[str, Any] = {
        "eventId": event_id(job_key, source_code, record.external_product_id, record.source_version, record.event_type),
        "eventType": record.event_type,
        "schemaVersion": SCHEMA_VERSION,
        "sourceCode": source_code,
        "externalProductId": record.external_product_id,
        "sourceVersion": record.source_version,
        "occurredAt": format_instant(record.occurred_at),
        "metadata": {
            "correlationId": f"batch:{job_key}",
            "producer": PRODUCER,
            "ingestionJobId": ingestion_job_id,
            "acceptedAt": format_instant(accepted_at or datetime.now(timezone.utc)),
        },
    }
    if record.event_type == UPSERTED:
        payload: dict[str, Any] = {"title": record.title}
        for key in ("brand", "model", "category", "gtin"):
            value = getattr(record, key)
            if value is not None:
                payload[key] = value
        if record.attributes:
            payload["attributes"] = dict(record.attributes)
        event["payload"] = payload
    return event


def message_attributes(event: dict[str, Any]) -> dict[str, dict[str, str]]:
    """SQS message attributes, same names as the Java producers (``MessageAttributes``)."""
    return {
        "eventType": {"DataType": "String", "StringValue": event["eventType"]},
        "schemaVersion": {"DataType": "String", "StringValue": str(event["schemaVersion"])},
        "eventId": {"DataType": "String", "StringValue": event["eventId"]},
        "correlationId": {"DataType": "String", "StringValue": event["metadata"]["correlationId"]},
    }
