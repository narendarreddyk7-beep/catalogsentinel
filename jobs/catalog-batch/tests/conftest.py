"""Test fixtures: a local Spark session, an in-process AWS emulator (moto server: S3 + SQS) and an in-memory
implementation of the ingestion-jobs API with the same contract and state machine as the Java service.

Environment variables are set at import time, before Spark starts, because PySpark's Python workers inherit the
environment of the JVM that launches them.
"""
from __future__ import annotations

import copy
import json
import os
import socket
import sys
from pathlib import Path
from typing import Any

import pytest


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


MOTO_PORT = _free_port()
ENDPOINT = f"http://127.0.0.1:{MOTO_PORT}"
os.environ.update({
    "AWS_ACCESS_KEY_ID": "testing", "AWS_SECRET_ACCESS_KEY": "testing", "AWS_SESSION_TOKEN": "testing",
    "AWS_REGION": "us-east-1", "AWS_DEFAULT_REGION": "us-east-1", "S3_ENDPOINT": ENDPOINT,
})
os.environ["PYSPARK_PYTHON"] = sys.executable  # workers need this interpreter's packages (boto3)
os.environ.pop("JAVA_TOOL_OPTIONS", None)  # sandbox proxy settings are irrelevant to a local Spark JVM

REPO = Path(__file__).resolve().parents[3]
EVENT_SCHEMA = json.loads((REPO / "libs/event-contracts/src/main/resources/schemas/listing-event.v1.json")
                          .read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def moto_server():
    from moto.server import ThreadedMotoServer

    server = ThreadedMotoServer(ip_address="127.0.0.1", port=MOTO_PORT, verbose=False)
    server.start()
    yield ENDPOINT
    server.stop()


@pytest.fixture()
def aws(moto_server):
    """Fresh bucket and queue per test (moto server state is reset)."""
    import boto3
    import urllib.request

    urllib.request.urlopen(urllib.request.Request(f"{moto_server}/moto-api/reset", method="POST")).read()
    s3 = boto3.client("s3", endpoint_url=moto_server, region_name="us-east-1")
    sqs = boto3.client("sqs", endpoint_url=moto_server, region_name="us-east-1")
    s3.create_bucket(Bucket="catalogsentinel-batch")
    queue_url = sqs.create_queue(QueueName="catalog-listing-events")["QueueUrl"]
    return {"endpoint": moto_server, "s3": s3, "sqs": sqs, "queue_url": queue_url}


@pytest.fixture(scope="session")
def spark():
    from pyspark.sql import SparkSession

    session = (SparkSession.builder.master("local[2]").appName("catalog-batch-tests")
               .config("spark.ui.enabled", "false")
               .config("spark.sql.shuffle.partitions", "4")
               .config("spark.sql.session.timeZone", "UTC")
               .config("spark.driver.memory", "1g")
               .getOrCreate())
    session.sparkContext.setLogLevel("ERROR")
    yield session
    session.stop()


def drain_queue(sqs, queue_url: str) -> list[dict[str, Any]]:
    messages = []
    while True:
        batch = sqs.receive_message(QueueUrl=queue_url, MaxNumberOfMessages=10, MessageAttributeNames=["All"],
                                    WaitTimeSeconds=0).get("Messages", [])
        if not batch:
            return messages
        messages.extend(batch)
        sqs.delete_message_batch(QueueUrl=queue_url, Entries=[
            {"Id": str(i), "ReceiptHandle": m["ReceiptHandle"]} for i, m in enumerate(batch)])


class FakeJobsApi:
    """In-memory stand-in for /api/v1/ingestion/jobs mirroring IngestionJobService's rules."""

    def __init__(self, known_sources=("northwind-supply", "acme-market")):
        self.jobs: dict[int, dict[str, Any]] = {}
        self.by_key: dict[str, int] = {}
        self.known_sources = set(known_sources)
        self.calls: list[tuple[str, Any]] = []

    def register(self, body):
        from catalog_batch.api_client import ApiError

        self.calls.append(("register", body))
        if body["sourceCode"] not in self.known_sources:
            raise ApiError(422, "UNKNOWN_SOURCE", "source is not registered")
        if body["jobKey"] in self.by_key:
            job = self.jobs[self.by_key[body["jobKey"]]]
            if job["manifest"] != body["manifest"] or job["job"]["sourceCode"] != body["sourceCode"]:
                raise ApiError(409, "JOB_KEY_REUSED", "different manifest")
            return copy.deepcopy(job)
        job_id = len(self.jobs) + 1
        self.jobs[job_id] = {"job": {"jobId": job_id, "jobKey": body["jobKey"], "sourceCode": body["sourceCode"],
                                     "status": "REGISTERED", "attempt": 1, "rowsTotal": body.get("rowsTotal"),
                                     "rowsValid": None, "rowsRejected": None, "eventsPublished": None,
                                     "outputUri": body.get("outputUri"), "rejectedUri": body.get("rejectedUri")},
                             "manifest": copy.deepcopy(body["manifest"]), "errors": [], "progress": {}}
        self.by_key[body["jobKey"]] = job_id
        return copy.deepcopy(self.jobs[job_id])

    def update(self, job_id, body):
        from catalog_batch.api_client import ApiError

        self.calls.append(("update", body))
        record = self.jobs[job_id]
        job = record["job"]
        to, frm = body["status"], job["status"]
        if frm == "PUBLISHED":
            raise ApiError(409, "JOB_ALREADY_PUBLISHED", "already published")
        if to == "RUNNING" and frm == "FAILED":
            job["attempt"] += 1
        if to == "PUBLISHED":
            if frm != "RUNNING":
                raise ApiError(409, "INVALID_JOB_TRANSITION", frm)
            merged = {k: body.get(k, job.get(k)) for k in ("rowsTotal", "rowsValid", "rowsRejected", "eventsPublished")}
            if None in merged.values() or merged["rowsValid"] + merged["rowsRejected"] != merged["rowsTotal"] \
                    or merged["eventsPublished"] > merged["rowsValid"]:
                raise ApiError(400, "VALIDATION_FAILED", f"inconsistent counts {merged}")
        for k in ("rowsTotal", "rowsValid", "rowsRejected", "eventsPublished", "outputUri", "rejectedUri"):
            if body.get(k) is not None:
                job[k] = body[k]
        if body.get("errors"):
            record["errors"].extend(body["errors"])
        job["status"] = to
        return copy.deepcopy(record)

    def get(self, job_id):
        return copy.deepcopy(self.jobs[job_id])


@pytest.fixture()
def api():
    return FakeJobsApi()
