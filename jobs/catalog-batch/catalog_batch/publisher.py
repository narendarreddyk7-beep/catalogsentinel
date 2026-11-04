"""Publishes listing events to SQS from Spark executors, one partition at a time.

Restart safety: after a partition's events are all accepted by SQS, a completion marker is written; a restarted
job skips partitions that have a marker and re-sends the rest. Events have deterministic ids, so a partition that
was partially sent before a crash is re-sent in full and the consumer's idempotency ledger drops the copies.
SQS itself is at-least-once; nothing here assumes exactly-once delivery.
"""
from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from typing import Any, Iterable, Iterator, Optional

from . import storage

SQS_BATCH = 10


@dataclass(frozen=True)
class PublishConfig:
    queue_name: str
    state_uri: str
    region: str
    endpoint: Optional[str]
    max_attempts: int = 6
    # Failure injection for restart tests and the failure demo: partitions with index >= this value raise
    # before sending anything, deterministically, regardless of task scheduling order.
    fail_from_partition: Optional[int] = None


def marker_uri(state_uri: str, partition: int) -> str:
    return storage.join(state_uri, "published", f"part-{partition:05d}.done")


def _sqs(config: PublishConfig):
    import boto3

    return boto3.client("sqs", region_name=config.region, endpoint_url=config.endpoint)


def send_all(client: Any, queue_url: str, entries: list[dict[str, Any]], max_attempts: int) -> None:
    """Sends events in batches of 10, retrying only the failed entries with jittered backoff."""
    for start in range(0, len(entries), SQS_BATCH):
        pending = entries[start:start + SQS_BATCH]
        attempt = 0
        while pending:
            attempt += 1
            response = client.send_message_batch(QueueUrl=queue_url, Entries=pending)
            failed_ids = {f["Id"] for f in response.get("Failed", [])}
            if not failed_ids:
                break
            if attempt >= max_attempts:
                raise RuntimeError(f"SQS rejected {len(failed_ids)} message(s) after {attempt} attempts: "
                                   f"{response.get('Failed')}")
            pending = [e for e in pending if e["Id"] in failed_ids]
            time.sleep(random.uniform(0, min(5.0, 0.1 * 2 ** attempt)))


def publish_partition(index: int, events: Iterable[str], config: PublishConfig) -> Iterator[tuple[int, int, bool]]:
    """``mapPartitionsWithIndex`` function: yields (partition, events sent, skipped)."""
    marker = marker_uri(config.state_uri, index)
    if storage.exists(marker):
        yield index, 0, True
        return
    if config.fail_from_partition is not None and index >= config.fail_from_partition:
        raise RuntimeError(f"injected failure before publishing partition {index}")
    client = _sqs(config)
    queue_url = client.get_queue_url(QueueName=config.queue_name)["QueueUrl"]
    entries: list[dict[str, Any]] = []
    sent = 0
    for body in events:
        event = json.loads(body)
        from .events import message_attributes  # local import keeps executor pickling small

        entries.append({"Id": f"m{len(entries)}", "MessageBody": body, "MessageAttributes": message_attributes(event)})
        if len(entries) == SQS_BATCH:
            send_all(client, queue_url, entries, config.max_attempts)
            sent += len(entries)
            entries = []
    if entries:
        send_all(client, queue_url, entries, config.max_attempts)
        sent += len(entries)
    storage.write_text(marker, json.dumps({"partition": index, "events": sent}))
    yield index, sent, False

