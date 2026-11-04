#!/usr/bin/env python3
"""Failure drills against the local compose stack (docs/runbook.md#failure-drills).

Each drill states what should happen, provokes the failure, and checks the outcome; it exits 0 only if every check
holds. Waits have deadlines inside a hard limit per drill (--max-seconds); a failed check or a timeout prints
diagnostics (queue depths, outbox, dead letters, compose state, recent worker logs) and exits 1. Exit 2 for usage
or environment errors (no docker compose, the demo sources not registered).

  db-outage       PostgreSQL stopped for 30 s under load: consumers pause instead of dead-lettering, every event
                  is applied once after the restart.
  worker-crash    The worker is killed (SIGKILL) while processing: in-flight messages reappear after their
                  visibility timeout and every event is applied exactly once in effect.
  poison-message  Malformed JSON on the listing queue goes straight to the DLQ with failure reason MALFORMED_JSON;
                  nothing else is affected.
  batch-crash     A batch job crashes mid-publish (injected), is reported FAILED, and a re-run resumes as attempt 2
                  from its partition markers; the worker processes every event exactly once.

Requires the stack (docker compose up -d --wait) and the demo sources (./scripts/seed-demo.sh). Drill data uses
the prefix DRILL-<scenario>-<timestamp>, so drills can run repeatedly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path
from typing import Callable, Optional

import smoke
from smoke import Api, Deadline, Observation, SmokeFailure, UsageError, depth, dlq_growth, poll, qs

REPO = smoke.REPO
LISTING_QUEUE = "catalog-listing-events"
LISTING_DLQ = "catalog-listing-events-dlq"
REFERENCE = "northwind-supply"
BATCH_SOURCE = "contoso-wholesale"


def log(message: str) -> None:
    print(message, flush=True)


def derive_job_key(manifest: dict) -> str:
    """Same rule as catalog_batch.manifest.derive_job_key: <sourceCode>:<sha256(canonical JSON)[:24]>."""
    canonical = json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return f"{manifest['sourceCode']}:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()[:24]}"


def write_drill_export(directory: Path, source: str, export_id: str, prefix: str, count: int) -> dict:
    """A JSONL export of ``count`` valid listings and its manifest; returns the manifest."""
    directory.mkdir(parents=True, exist_ok=True)
    lines = []
    for i in range(count):
        lines.append(json.dumps({
            "externalProductId": f"{prefix}-{i:05d}", "sourceVersion": 1, "updatedAt": "2026-05-01T00:00:00Z",
            "title": f"Drill batch item {prefix} {i:05d}", "brand": "Drillworks", "category": "Drills > Batch",
            "attributes": {"synthetic_demand_index": "0.1000"}}, separators=(",", ":")))
    text = "\n".join(lines) + "\n"
    (directory / "listings-00001.jsonl").write_text(text, encoding="utf-8")
    manifest = {"manifestVersion": 1, "sourceCode": source, "exportId": export_id,
                "exportedAt": "2026-05-01T00:00:00Z", "format": "jsonl", "syntheticData": True,
                "files": [{"path": "listings-00001.jsonl", "records": count,
                           "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest()}]}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


class Drill:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.api = Api(args.api, args.api_key, timeout=args.request_timeout)
        self.overall = Deadline(args.max_seconds)
        self.queues_before: dict = {}
        self.run_id = f"DRILL-{args.scenario}-{int(time.time())}"

    # ------------------------------------------------------------------ infrastructure

    def remaining(self, what: str) -> float:
        left = self.overall.remaining()
        if left <= 0:
            raise SmokeFailure(f"{what}: the drill exceeded --max-seconds={self.args.max_seconds:.0f}")
        return left

    def compose(self, *args: str, stdin: Optional[str] = None, what: str, check: bool = True,
                profile: bool = False) -> subprocess.CompletedProcess:
        cmd = ["docker", "compose", *(["--profile", "batch"] if profile else []), *args]
        try:
            p = subprocess.run(cmd, cwd=REPO, input=stdin, text=True, capture_output=True,
                               timeout=self.remaining(what))
        except subprocess.TimeoutExpired as e:
            raise SmokeFailure(f"{what}: did not finish before the drill's deadline",
                               {"stdout": _tail(e.stdout), "stderr": _tail(e.stderr)}) from None
        if check and p.returncode != 0:
            raise SmokeFailure(f"{what} failed (exit {p.returncode})", {"stdout": _tail(p.stdout),
                                                                       "stderr": _tail(p.stderr)})
        return p

    def psql(self, sql: str) -> str:
        return self.compose("exec", "-T", "postgres", "psql", "-U", "catalog", "-d", "catalogsentinel", "-Atc", sql,
                            what="psql").stdout.strip()

    def count(self, sql: str) -> int:
        return int(self.psql(sql) or 0)

    def queues(self) -> dict:
        return self.api.get("/api/v1/metrics/processing?windowMinutes=60").get("queues", {})

    def dlq_depth(self) -> Optional[int]:
        return depth(self.queues().get(LISTING_DLQ, {}))

    def no_new_dead_letters(self) -> None:
        grown = dlq_growth(self.queues_before, self.queues())
        if grown:
            raise SmokeFailure("messages reached a dead-letter queue: " + ", ".join(
                f"{q} {a} -> {b}" for q, (a, b) in grown.items()), {q: self.peek(q) for q in grown})

    def peek(self, queue: str) -> list:
        body = self.api.get("/api/v1/admin/dead-letters?" + qs(queue=queue, max=10))
        return [{"failureReason": m.get("attributes", {}).get("failureReason"), "body": (m.get("body") or "")[:200]}
                for m in body.get("messages", [])]

    def wait(self, what: str, probe: Callable[[], Observation], timeout: float, interval: float = 2.0,
             guard: Optional[Callable[[], None]] = None):
        return poll(what, probe, timeout=min(timeout, self.remaining(what)), interval=interval, guard=guard,
                    overall=self.overall)

    def send_events(self, count: int, prefix: str) -> list:
        """Enqueues ``count`` new listings for the reference source through the API's queued path."""
        ids = []
        for start in range(0, count, 500):
            events = [{"eventId": str(uuid.uuid4()), "eventType": "LISTING_UPSERTED", "schemaVersion": 1,
                       "sourceCode": REFERENCE, "externalProductId": f"{prefix}-{i:05d}", "sourceVersion": 1,
                       "occurredAt": "2026-05-01T00:00:00Z", "payload": {"title": f"Drill item {prefix} {i:05d}"}}
                      for i in range(start, min(count, start + 500))]
            r = self.api.call("POST", "/api/v1/ingestion/events", {"events": events}, expect=(202,))
            if r.body.get("accepted") != len(events):
                raise SmokeFailure("the API did not queue every drill event", r.body)
            ids += [e["eventId"] for e in events]
        return ids

    def listings(self, prefix: str) -> int:
        return self.count(f"SELECT count(*) FROM product_listings WHERE external_product_id LIKE '{prefix}-%'")

    def ledger(self, prefix: str) -> int:
        return self.count(f"SELECT count(*) FROM processed_events WHERE external_product_id LIKE '{prefix}-%'")

    def wait_for_listings(self, prefix: str, expected: int, timeout: float) -> None:
        def probe() -> Observation:
            n = self.listings(prefix)
            return Observation(n == expected, n, {"listings": n, "expected": expected})

        self.wait(f"{expected} listings with prefix {prefix}", probe, timeout, interval=3)

    def check(self, condition: bool, message: str, details=None) -> None:
        if not condition:
            raise SmokeFailure(message, details)
        log(f"  ok   {message}")

    # ------------------------------------------------------------------ preflight

    def preflight(self) -> None:
        if subprocess.run(["docker", "compose", "version"], capture_output=True, text=True).returncode != 0:
            raise UsageError("docker compose is required: the drills stop and kill containers of the local stack")

        def ready() -> Observation:
            try:
                r = self.api.call("GET", "/actuator/health/readiness", expect=None)
            except SmokeFailure as e:
                return Observation(False, observed=str(e))
            return Observation(r.status == 200, observed=r.status)

        self.wait("API readiness (start the stack with: docker compose up -d --build --wait)", ready,
                  self.args.ready_seconds)
        sources = {s["code"]: s for s in self.api.get("/api/v1/sources?" + qs(limit=200))["items"]}
        missing = [c for c in (REFERENCE, BATCH_SOURCE) if not sources.get(c, {}).get("enabled")]
        if missing:
            raise UsageError(f"demo sources not registered or disabled: {missing}; run ./scripts/seed-demo.sh first")
        self.queues_before = self.queues()
        log(f"  ok   stack ready; {LISTING_DLQ} depth {depth(self.queues_before.get(LISTING_DLQ, {}))}")

    # ------------------------------------------------------------------ scenarios

    def db_outage(self) -> None:
        log("Expect: consumers pause after consecutive connection failures, nothing is dead-lettered, every event "
            "is applied once after PostgreSQL returns.")
        prefix = self.run_id
        self.send_events(2000, prefix)
        log("  ..   2000 events queued; stopping postgres for 30 s")
        self.compose("stop", "postgres", what="docker compose stop postgres")
        try:
            time.sleep(min(30, self.remaining("outage")))
        finally:
            self.compose("start", "postgres", what="docker compose start postgres")
        self.wait_for_listings(prefix, 2000, timeout=self.args.wait_seconds)
        self.check(self.ledger(prefix) == 2000, "2000/2000 listings, each event recorded once in the ledger")
        self.no_new_dead_letters()
        log("  ok   nothing was dead-lettered")
        logs = self.compose("logs", "--no-color", "--since", "10m", "ingestion-worker", what="worker logs").stdout
        pauses = logs.count("pausing consumption")
        log(f"  info worker log: {pauses} 'pausing consumption' line(s), "
            f"{logs.count('dependency healthy again')} 'dependency healthy again' line(s)")

    def worker_crash(self) -> None:
        log("Expect: in-flight messages reappear after their visibility timeout and every event is applied exactly "
            "once in effect.")
        prefix = self.run_id
        self.send_events(3000, prefix)
        time.sleep(3)
        in_progress = self.listings(prefix)
        self.compose("kill", "-s", "KILL", "ingestion-worker", what="docker compose kill -s KILL ingestion-worker")
        log(f"  ..   worker killed with {in_progress}/3000 listings applied; restarting in 5 s")
        time.sleep(5)
        self.compose("start", "ingestion-worker", what="docker compose start ingestion-worker")
        self.wait_for_listings(prefix, 3000, timeout=self.args.wait_seconds)
        self.check(self.ledger(prefix) == 3000, "3000/3000 listings and exactly one ledger entry per event")
        self.no_new_dead_letters()
        log("  ok   nothing was dead-lettered")

    def poison_message(self) -> None:
        log("Expect: malformed JSON goes straight to the DLQ with failure reason MALFORMED_JSON; replay is possible "
            "after a fix.")
        before = self.dlq_depth() or 0
        body = f"{{not json {self.run_id}"
        script = ("import boto3, os, sys\n"
                  "sqs = boto3.client('sqs', endpoint_url=os.environ['SQS_ENDPOINT'], region_name=os.environ['AWS_REGION'])\n"
                  f"url = sqs.get_queue_url(QueueName='{LISTING_QUEUE}')['QueueUrl']\n"
                  "sqs.send_message(QueueUrl=url, MessageBody=sys.argv[1])\n")
        self.compose("run", "--rm", "-T", "--entrypoint", "python", "batch", "-c", script, body,
                     what="sending a malformed message", profile=True)

        def dead_lettered() -> Observation:
            n = self.dlq_depth() or 0
            return Observation(n > before, n, {"dlqDepth": n, "before": before})

        self.wait(f"the malformed message to reach {LISTING_DLQ}", dead_lettered, timeout=120)

        def found() -> Observation:
            peeked = self.peek(LISTING_DLQ)
            return Observation(any(self.run_id in (m["body"] or "") and (m["failureReason"] or "").startswith(
                "MALFORMED_JSON") for m in peeked), peeked, peeked)

        # Peeking samples the queue, so look a few times for this drill's message.
        self.wait("the drill's message in the DLQ with failure reason MALFORMED_JSON", found, timeout=30, interval=2)
        self.check(True, f"{LISTING_DLQ} depth {before} -> {self.dlq_depth()}; reason MALFORMED_JSON recorded")
        log("  info replay with POST /api/v1/admin/dead-letters/replay only after fixing the cause; this message "
            "is malformed, so it would return to the DLQ")

    def batch_crash(self) -> None:
        log("Expect: the batch job fails mid-publish, is reported FAILED, and a re-run resumes as attempt 2 from its "
            "partition markers without losing or duplicating events.")
        count = 2000
        prefix = self.run_id
        export_id = prefix.lower()
        directory = REPO / "build" / "drills" / export_id
        manifest = write_drill_export(directory, BATCH_SOURCE, export_id, prefix, count)
        job_key = derive_job_key(manifest)
        bucket = self.args.bucket
        base = f"incoming/{BATCH_SOURCE}/{export_id}/"
        stage = ("import os, sys, boto3\n"
                 "s3 = boto3.client('s3', endpoint_url=os.environ.get('S3_ENDPOINT') or None, "
                 "region_name=os.environ['AWS_REGION'])\n"
                 "bucket, base = sys.argv[1], sys.argv[2]\n"
                 "if bucket not in [b['Name'] for b in s3.list_buckets().get('Buckets', [])]:\n"
                 "    s3.create_bucket(Bucket=bucket)\n"
                 "for name in os.listdir('/data'):\n"
                 "    s3.upload_file(f'/data/{name}', bucket, base + name)\n")
        self.compose("run", "--rm", "-T", "-v", f"{directory}:/data:ro", "--entrypoint", "python", "batch", "-c",
                     stage, bucket, base, what="staging the drill export", profile=True)
        args = ["run", "--rm", "-T", "batch", "run", "--manifest", f"s3://{bucket}/{base}manifest.json",
                "--output", f"s3://{bucket}/curated/listings", "--rejected", f"s3://{bucket}/rejected",
                "--state", f"s3://{bucket}/_state"]
        first = self.compose(*args, "--fail-from-partition", "3", what="the batch job (crash injected)", check=False,
                             profile=True)
        self.check(first.returncode != 0, f"the first attempt crashed as injected (exit {first.returncode})",
                   {"stdout": _tail(first.stdout), "stderr": _tail(first.stderr)})
        jobs = [j for j in self.api.get("/api/v1/ingestion/jobs?" + qs(sourceCode=BATCH_SOURCE, limit=20))["items"]
                if j["jobKey"] == job_key]
        self.check(len(jobs) == 1 and jobs[0]["status"] == "FAILED", f"job {job_key} is reported FAILED", jobs)
        job_id = jobs[0]["jobId"]
        sent_before = self.ledger_after_settle(prefix)
        log(f"  ..   {sent_before} of {count} events reached the worker before the crash")

        second = self.compose(*args, what="the batch job (re-run)", check=False, profile=True)
        try:
            result = smoke.last_json_object(second.stdout)
        except ValueError:
            raise SmokeFailure(f"the re-run exited {second.returncode} without a result",
                               {"stdout": _tail(second.stdout), "stderr": _tail(second.stderr)}) from None
        self.check(second.returncode == 0 and result.get("status") == "PUBLISHED" and result.get("attempt") == 2
                   and result.get("job_id") == job_id and result.get("events_published") == count
                   and result.get("partitions_skipped", 0) >= 1,
                   f"the re-run resumed job {job_id} as attempt 2, skipped {result.get('partitions_skipped')} "
                   f"completed partition(s) and published {result.get('events_published')} events", result)

        def processed() -> Observation:
            view = self.api.get(f"/api/v1/ingestion/jobs/{job_id}")
            return Observation(bool(view["progress"]["fullyProcessed"]), view, view["progress"])

        view = self.wait(f"job {job_id} to be fully processed", processed, timeout=self.args.wait_seconds, interval=3,
                         guard=self.no_new_dead_letters)
        self.check(view["progress"]["processedEvents"] == count and self.listings(prefix) == count
                   and self.ledger(prefix) == count,
                   f"{count}/{count} events processed exactly once ({view['progress']['byOutcome']})", view["progress"])
        self.no_new_dead_letters()
        log("  ok   nothing was dead-lettered")

    def ledger_after_settle(self, prefix: str) -> int:
        """Ledger rows for the prefix once they stop changing (the worker drains what the crashed run sent)."""
        last, stable = -1, 0
        while stable < 3 and self.remaining("settling"):
            n = self.ledger(prefix)
            stable = stable + 1 if n == last else 0
            last = n
            time.sleep(2)
        return last


def _tail(text, n: int = 3000):
    return text[-n:] if isinstance(text, str) else text


def diagnostics(drill: Drill) -> dict:
    out: dict = {"runId": drill.run_id}
    api = Api(drill.args.api, drill.args.api_key, timeout=5)

    def attempt(name, fn):
        try:
            out[name] = fn()
        except Exception as e:  # best effort
            out[name] = f"unavailable: {e}"

    attempt("queuesAndOutbox", lambda: {k: v for k, v in api.get("/api/v1/metrics/processing?windowMinutes=60").items()
                                        if k in ("queues", "outbox")})
    attempt("deadLetters", lambda: drill.peek(LISTING_DLQ))
    for cmd, name in ((["docker", "compose", "ps", "--format", "{{.Service}} {{.State}} {{.Health}}"], "compose"),
                      (["docker", "compose", "logs", "--no-color", "--tail", "40", "ingestion-worker"], "workerLogs")):
        try:
            p = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=30)
            out[name] = p.stdout[-5000:].splitlines() if name == "compose" else p.stdout[-5000:]
        except Exception as e:  # best effort
            out[name] = f"unavailable: {e}"
    return out


SCENARIOS = {"db-outage": Drill.db_outage, "worker-crash": Drill.worker_crash,
             "poison-message": Drill.poison_message, "batch-crash": Drill.batch_crash}


def parse_args(argv: Optional[list] = None) -> argparse.Namespace:
    env = os.environ
    p = argparse.ArgumentParser(description="Failure drills against the local compose stack.")
    p.add_argument("scenario", choices=sorted(SCENARIOS))
    p.add_argument("--api", default=env.get("API", "http://localhost:8080"))
    p.add_argument("--api-key", default=env.get("API_KEY", ""))
    p.add_argument("--max-seconds", type=float, default=float(env.get("DRILL_MAX_SECONDS", 900)),
                   help="hard limit for the drill (DRILL_MAX_SECONDS)")
    p.add_argument("--wait-seconds", type=float, default=float(env.get("DRILL_WAIT_SECONDS", 300)),
                   help="deadline for the recovery waits (DRILL_WAIT_SECONDS)")
    p.add_argument("--ready-seconds", type=float, default=float(env.get("DRILL_READY_SECONDS", 60)))
    p.add_argument("--request-timeout", type=float, default=float(env.get("DRILL_REQUEST_TIMEOUT", 15)))
    p.add_argument("--bucket", default=env.get("BATCH_BUCKET", "catalogsentinel-batch"))
    a = p.parse_args(argv)
    if min(a.max_seconds, a.wait_seconds, a.ready_seconds, a.request_timeout) <= 0:
        raise UsageError("time limits must be positive")
    return a


def main(argv: Optional[list] = None) -> int:
    drill: Optional[Drill] = None
    try:
        args = parse_args(argv)
        drill = Drill(args)
        log(f"Failure drill {args.scenario} ({drill.run_id}) against {args.api}; limit {args.max_seconds:.0f} s")
        drill.preflight()
        SCENARIOS[args.scenario](drill)
        log(f"{args.scenario}: OK")
        return 0
    except UsageError as e:
        print(f"inject-failure: {e}", file=sys.stderr)
        return 2
    except SmokeFailure as e:
        print(f"  FAIL {e}", file=sys.stderr)
        if e.details is not None:
            print("       details: " + json.dumps(e.details, default=str)[:6000], file=sys.stderr)
        if drill is not None:
            print("       diagnostics: " + json.dumps(diagnostics(drill), default=str)[:12000], file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
