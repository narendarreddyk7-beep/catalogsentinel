#!/usr/bin/env python3
"""Loads the SYNTHETIC demo catalog into a running local stack, with deadlines and diagnostics.

Steps: generate the exports (deterministic: seed 7, fixed export timestamps), register the three demo sources,
stage the exports in the S3 emulator, run the PySpark batch job once per source, wait until every job's events are
processed by the worker and the change queue has drained (matching done), then run one reconciliation for
acme-market and wait for it.

Repeatable: the exports are byte-identical on every run, so the batch job keys (derived from the manifests), the
event ids (UUIDv5 of job key, source, listing, version, type) and the reconciliation's Idempotency-Key (derived
from the job keys) are the same too. Running the seed again is a no-op: the batch jobs report skipped, the
reconciliation request returns the existing job.

Fails (exit 1) with diagnostics when a step exceeds its deadline or the whole run exceeds --max-seconds (the API
never becoming ready included), when a batch job or the reconciliation fails, or when a message reaches a
dead-letter queue. Exit 2 for usage or environment errors (bad arguments, no docker compose).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from typing import Any, Optional

import smoke
from smoke import Api, Deadline, Observation, SmokeFailure, UsageError, depth, dlq_growth, poll, qs

REPO = smoke.REPO
SOURCES = [("northwind-supply", "Northwind Supply", "REFERENCE"),
           ("contoso-wholesale", "Contoso Wholesale", "REFERENCE"),
           ("acme-market", "Acme Market", "MARKETPLACE")]
TARGET = "acme-market"
DEFAULT_MAX_SECONDS = {"10k": 1800, "100k": 7200}
STAGE_SCRIPT = """
import os, sys, boto3
bucket = sys.argv[1]
s3 = boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT") or None, region_name=os.environ["AWS_REGION"])
if bucket not in [b["Name"] for b in s3.list_buckets().get("Buckets", [])]:
    s3.create_bucket(Bucket=bucket)
count = 0
for source in sys.argv[2:]:
    for name in sorted(os.listdir(f"/data/{source}")):
        s3.upload_file(f"/data/{source}/{name}", bucket, f"incoming/{source}/demo/{name}")
        count += 1
print("staged", count)
"""


def log(message: str) -> None:
    print(message, flush=True)


def reconciliation_key(job_keys: list) -> str:
    """Same exports, same key: a repeated seed returns the existing reconciliation instead of starting another."""
    return "seed-demo-" + hashlib.sha256("|".join(sorted(job_keys)).encode()).hexdigest()[:24]


class Seed:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.api = Api(args.api, args.api_key, timeout=args.request_timeout)
        self.overall = Deadline(args.max_seconds)
        self.out = REPO / "build" / f"demo-{args.size}"
        self.queues_before: dict = {}
        self.job_ids: dict = {}
        self.step = "start"

    def remaining(self, what: str) -> float:
        left = self.overall.remaining()
        if left <= 0:
            raise SmokeFailure(f"{what}: the seed exceeded --max-seconds={self.args.max_seconds:.0f}")
        return left

    def wait(self, what: str, probe, interval: float = 5.0) -> Any:
        return poll(what, probe, timeout=self.remaining(what), interval=interval, guard=self.check_dead_letters,
                    overall=self.overall)

    def queues(self) -> dict:
        return self.api.get("/api/v1/metrics/processing?windowMinutes=60").get("queues", {})

    def check_dead_letters(self) -> None:
        grown = dlq_growth(self.queues_before, self.queues())
        if grown:
            raise SmokeFailure("messages reached a dead-letter queue while seeding: "
                               + ", ".join(f"{q} {a} -> {b}" for q, (a, b) in grown.items()),
                               {q: self.peek(q) for q in grown})

    def peek(self, queue: str) -> Any:
        try:
            body = self.api.get("/api/v1/admin/dead-letters?" + qs(queue=queue, max=10))
            return [{"failureReason": m.get("attributes", {}).get("failureReason"),
                     "body": (m.get("body") or "")[:300]} for m in body.get("messages", [])]
        except SmokeFailure as e:
            return f"peek failed: {e}"

    def compose(self, *args: str, stdin: Optional[str] = None, what: str) -> subprocess.CompletedProcess:
        self.step = what
        cmd = ["docker", "compose", "--profile", "batch", *args]
        try:
            return subprocess.run(cmd, cwd=REPO, input=stdin, text=True, capture_output=True,
                                  timeout=self.remaining(what))
        except subprocess.TimeoutExpired as e:
            raise SmokeFailure(f"{what}: did not finish before the seed's deadline",
                               {"stdout": _tail(e.stdout), "stderr": _tail(e.stderr)}) from None

    # ---------------------------------------------------------------- steps

    def preflight(self) -> None:
        self.step = "preflight"
        probe = subprocess.run(["docker", "compose", "version"], capture_output=True, text=True)
        if probe.returncode != 0:
            raise UsageError("docker compose is required (the batch job runs in the compose stack)")

        def ready() -> Observation:
            try:
                r = self.api.call("GET", "/actuator/health/readiness", expect=None)
            except SmokeFailure as e:
                return Observation(False, observed=str(e))
            return Observation(r.status == 200, observed=r.status)

        poll("API readiness (start the stack with: docker compose up -d --build --wait)", ready,
             timeout=min(self.args.ready_seconds, self.remaining("readiness")), overall=self.overall)
        self.queues_before = self.queues()
        log(f"   API ready at {self.args.api}; dead-letter depths "
            f"{ {q: depth(e) for q, e in self.queues_before.items() if q.endswith('-dlq')} }")

    def generate(self) -> None:
        self.step = "generate"
        log(f"1/6 generating the synthetic exports ({self.args.size}, seed 7) into {self.out.relative_to(REPO)}")
        cmd = [sys.executable, str(REPO / "scripts" / "generate-synthetic-data.py"), "--size", self.args.size,
               "--out", str(self.out), "--seed", "7"]
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=self.remaining("generate"))
        except subprocess.TimeoutExpired:
            raise SmokeFailure("generating the exports did not finish before the deadline") from None
        if p.returncode != 0:
            raise SmokeFailure("the data generator failed", {"stderr": _tail(p.stderr)})
        log(f"   {p.stdout.strip()}")

    def register_sources(self) -> None:
        self.step = "sources"
        log("2/6 registering the demo sources")
        for code, name, role in SOURCES:
            r = self.api.call("POST", "/api/v1/sources", {"code": code, "name": name, "role": role}, expect=(201, 409))
            if r.status == 409:
                existing = [s for s in self.api.get("/api/v1/sources?" + qs(limit=200))["items"] if s["code"] == code]
                if not existing or existing[0]["role"] != role or not existing[0]["enabled"]:
                    raise SmokeFailure(f"source {code} exists but is not an enabled {role} source", existing)
                log(f"   {code}: already registered")
            else:
                log(f"   {code}: registered ({role})")

    def stage(self) -> None:
        log(f"3/6 staging the exports in the S3 emulator (s3://{self.args.bucket}/incoming/<source>/demo/)")
        p = self.compose("run", "--rm", "-T", "-v", f"{self.out}:/data:ro", "--entrypoint", "python", "batch", "-",
                         self.args.bucket, *[s[0] for s in SOURCES], stdin=STAGE_SCRIPT, what="staging the exports")
        if p.returncode != 0:
            raise SmokeFailure("staging the exports failed", {"stdout": _tail(p.stdout), "stderr": _tail(p.stderr)})
        log(f"   {p.stdout.strip().splitlines()[-1] if p.stdout.strip() else 'staged'}")

    def run_batches(self) -> list:
        log("4/6 running the batch job per source")
        keys = []
        for code, _, _ in SOURCES:
            p = self.compose("run", "--rm", "-T", *(["-e", f"CATALOGSENTINEL_API_KEY={self.args.api_key}"]
                                                    if self.args.api_key else []),
                             "batch", "run", "--manifest", f"s3://{self.args.bucket}/incoming/{code}/demo/manifest.json",
                             "--output", f"s3://{self.args.bucket}/curated/listings",
                             "--rejected", f"s3://{self.args.bucket}/rejected", "--state", f"s3://{self.args.bucket}/_state",
                             what=f"the batch job for {code}")
            try:
                result = smoke.last_json_object(p.stdout)
            except ValueError:
                raise SmokeFailure(f"the batch job for {code} exited {p.returncode} without a result",
                                   {"stdout": _tail(p.stdout), "stderr": _tail(p.stderr)}) from None
            if p.returncode != 0 or result.get("status") != "PUBLISHED":
                raise SmokeFailure(f"the batch job for {code} ended {result.get('status')} (exit {p.returncode})",
                                   {"result": result, "stderr": _tail(p.stderr)})
            self.job_ids[code] = result["job_id"]
            keys.append(result["job_key"])
            log(f"   {code}: job {result['job_id']} ({result['job_key']}) {result['rows_total']} rows, "
                f"{result['rows_rejected']} rejected, {result['events_published']} events"
                + (" — already published, skipped" if result.get("skipped") else ""))
        return keys

    def await_processing(self) -> None:
        log("5/6 waiting for the worker: every batch event processed, then matching (change queue) drained")
        for code, job_id in self.job_ids.items():
            def processed(job_id=job_id) -> Observation:
                view = self.api.get(f"/api/v1/ingestion/jobs/{job_id}")
                p = view["progress"]
                return Observation(bool(p["fullyProcessed"]), view,
                                   {"processed": p["processedEvents"], "remaining": p["remainingEvents"]})

            self.step = f"processing job {job_id}"
            view = self.wait(f"batch job {job_id} ({code}) to be fully processed", processed)
            log(f"   {code}: {view['progress']['processedEvents']} events processed {view['progress']['byOutcome']}")

        def drained() -> Observation:
            backlog = {q: depth(e) for q, e in self.queues().items() if not q.endswith("-dlq")}
            return Observation(all(v == 0 for v in backlog.values()), backlog, backlog)

        self.step = "matching"
        self.wait("the listing and change queues to drain (matching finished)", drained)
        log("   queues drained")

    def reconcile(self, job_keys: list) -> None:
        self.step = "reconciliation"
        key = reconciliation_key(job_keys)
        log(f"6/6 reconciliation for {TARGET} (Idempotency-Key {key})")
        r = self.api.call("POST", "/api/v1/reconciliation/jobs", {"targetSourceCode": TARGET, "requestedBy": "seed-demo"},
                          headers={"Idempotency-Key": key}, expect=(202, 409))
        if r.status == 409:
            raise SmokeFailure(f"another reconciliation for {TARGET} is queued or running; wait for it and run the "
                               "seed again (it is idempotent)", r.body)
        job_id = r.body["jobId"]

        def finished() -> Observation:
            job = self.api.get(f"/api/v1/reconciliation/jobs/{job_id}")
            if job["status"] == "FAILED":
                raise SmokeFailure(f"reconciliation job {job_id} FAILED", job)
            return Observation(job["status"] == "SUCCEEDED", job,
                               {k: job.get(k) for k in ("status", "attempt", "errorMessage", "nextAttemptAt")})

        job = self.wait(f"reconciliation job {job_id} to succeed", finished)
        log(f"   job {job_id} SUCCEEDED: {job.get('stats')}")

    def summary(self) -> None:
        self.check_dead_letters()
        s = self.api.get("/api/v1/dashboard/summary")
        log(f"Done in {self.args.max_seconds - self.overall.remaining():.0f} s. Listings {s.get('listingsByStatus')}, "
            f"open gaps by priority {s.get('openGapsByPriority')}. Open http://localhost:3000 (all data is synthetic).")


def _tail(text: Any, n: int = 3000) -> Any:
    return text[-n:] if isinstance(text, str) else text


def diagnostics(seed: Seed) -> dict:
    out: dict = {"failedStep": seed.step}
    api = Api(seed.args.api, seed.args.api_key, timeout=5)

    def attempt(name, fn):
        try:
            out[name] = fn()
        except Exception as e:  # best effort
            out[name] = f"unavailable: {e}"

    attempt("queues", lambda: api.get("/api/v1/metrics/processing?windowMinutes=60").get("queues"))
    attempt("outbox", lambda: api.get("/api/v1/admin/outbox"))
    attempt("ingestionJobs", lambda: [{k: j.get(k) for k in ("jobId", "sourceCode", "status", "eventsPublished")}
                                      for j in api.get("/api/v1/ingestion/jobs?limit=5")["items"]])
    for code, job_id in seed.job_ids.items():
        attempt(f"progress:{code}", lambda job_id=job_id: api.get(f"/api/v1/ingestion/jobs/{job_id}")["progress"])
    for queue in [q for q in seed.queues_before if q.endswith("-dlq")]:
        attempt(f"deadLetters:{queue}", lambda q=queue: seed.peek(q))
    try:
        ps = subprocess.run(["docker", "compose", "ps", "--format", "{{.Service}} {{.State}} {{.Health}}"], cwd=REPO,
                            capture_output=True, text=True, timeout=20)
        out["compose"] = ps.stdout.strip().splitlines()
        logs = subprocess.run(["docker", "compose", "logs", "--no-color", "--tail", "40", "catalog-api",
                               "ingestion-worker"], cwd=REPO, capture_output=True, text=True, timeout=30)
        out["recentLogs"] = logs.stdout[-6000:]
    except Exception as e:  # best effort
        out["compose"] = f"unavailable: {e}"
    return out


def parse_args(argv: Optional[list] = None) -> argparse.Namespace:
    env = os.environ
    p = argparse.ArgumentParser(description="Load the synthetic demo catalog into the local stack.")
    p.add_argument("size", nargs="?", choices=sorted(DEFAULT_MAX_SECONDS), default="10k")
    p.add_argument("--api", default=env.get("API", "http://localhost:8080"))
    p.add_argument("--api-key", default=env.get("API_KEY", ""))
    p.add_argument("--max-seconds", type=float, default=None,
                   help="hard limit for the whole seed (SEED_MAX_SECONDS; default 1800 for 10k, 7200 for 100k)")
    p.add_argument("--ready-seconds", type=float, default=float(env.get("SEED_READY_SECONDS", 120)),
                   help="how long to wait for the API to become ready (SEED_READY_SECONDS)")
    p.add_argument("--request-timeout", type=float, default=float(env.get("SEED_REQUEST_TIMEOUT", 30)))
    p.add_argument("--bucket", default=env.get("BATCH_BUCKET", "catalogsentinel-batch"))
    a = p.parse_args(argv)
    if a.max_seconds is None:
        a.max_seconds = float(env.get("SEED_MAX_SECONDS", DEFAULT_MAX_SECONDS[a.size]))
    if a.max_seconds <= 0 or a.ready_seconds <= 0 or a.request_timeout <= 0:
        raise UsageError("time limits must be positive")
    return a


def main(argv: Optional[list] = None) -> int:
    seed: Optional[Seed] = None
    try:
        seed = Seed(parse_args(argv))
        log(f"Seeding the SYNTHETIC demo catalog ({seed.args.size}) into {seed.args.api}; "
            f"limit {seed.args.max_seconds:.0f} s")
        seed.preflight()
        seed.generate()
        seed.register_sources()
        seed.stage()
        keys = seed.run_batches()
        seed.await_processing()
        seed.reconcile(keys)
        seed.summary()
        return 0
    except UsageError as e:
        print(f"seed-demo: {e}", file=sys.stderr)
        return 2
    except SmokeFailure as e:
        print(f"seed-demo FAILED: {e}", file=sys.stderr)
        if e.details is not None:
            print("   details: " + json.dumps(e.details, default=str)[:6000], file=sys.stderr)
        if seed is not None:
            print("   diagnostics: " + json.dumps(diagnostics(seed), default=str)[:16000], file=sys.stderr)
        print("   The seed is idempotent: fix the cause and run it again.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
