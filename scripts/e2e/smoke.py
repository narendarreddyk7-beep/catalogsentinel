#!/usr/bin/env python3
"""End-to-end smoke test against a running CatalogSentinel stack.

Drives the fixtures in smoke-scenario.json through the public API, SQS, the worker and (by default) the batch container,
and checks every result exactly: ingestion semantics (create, replay, reused id, stale, conflict, withdraw,
reactivate), redelivery without duplicates, reference and marketplace listings converging on one canonical product,
the exact gaps and coverage after reconciliation, audited match and gap decisions, a batch job that publishes and is
fully processed, and filtered queries and aggregates over the fixtures. It fails on the first wrong result, on any
wait that runs past its deadline, and as soon as a message reaches a dead-letter queue.

SmokeScenarioIT (libs/catalog-core) runs the same scenario in-process against PostgreSQL, so the expected outcomes
are checked against the real code by the Java build; this script checks the deployed wiring around it.

Every run uses a fresh token: its own three sources, run-specific brand, category root and GTINs, so runs never
interfere with each other or with seeded demo data. At the end the run's sources are disabled (--keep leaves them).

Exit codes: 0 passed, 1 a check failed or timed out (diagnostics printed), 2 usage or environment error.
Standard library only (Python 3.9+).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import signal
import socket
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Optional

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
SCENARIO_FILE = HERE / "smoke-scenario.json"
UPSERTED, WITHDRAWN = "LISTING_UPSERTED", "LISTING_WITHDRAWN"
EVENT_NAMESPACE = uuid.UUID("0b3c6f2e-5d6a-4a59-9f0e-8b1f6c2d7e41")
TOKEN_RE = re.compile(r"[a-z]{4,24}")
GTIN_RE = re.compile(r"\{gtin:([a-z0-9-]+)\}")


class SmokeFailure(Exception):
    """A check failed or timed out. ``details`` is printed as JSON."""

    def __init__(self, message: str, details: Any = None):
        super().__init__(message)
        self.details = details


class UsageError(Exception):
    """Bad arguments or a missing tool: exit code 2."""


# ---------------------------------------------------------------------------------------------------- scenario

def gs1_check_digit(body: str) -> int:
    total = sum(int(d) * (3 if i % 2 else 1) for i, d in enumerate(body))
    return (10 - total % 10) % 10


def gtin_for(token: str, name: str) -> str:
    """'2' + 11 digits from SHA-256(token:name) + check digit; identical to SmokeScenario.gtinFor (Java)."""
    value = int.from_bytes(hashlib.sha256(f"{token}:{name}".encode()).digest()[:8], "big") % 10 ** 11
    body = "2" + f"{value:011d}"
    return body + str(gs1_check_digit(body))


def new_token() -> str:
    return "".join(secrets.choice(string.ascii_lowercase) for _ in range(10))


def fill(text: str, token: str) -> str:
    brand = token[0].upper() + token[1:]
    out = (text.replace("{root}", "smoke " + token).replace("{token}", token)
           .replace("{Brand}", brand).replace("{BRAND}", token.upper()))
    return GTIN_RE.sub(lambda m: gtin_for(token, m.group(1)), out)


def instantiate(value: Any, token: str) -> Any:
    if not TOKEN_RE.fullmatch(token):
        raise UsageError(f"run token must be 4-24 lowercase letters, got {token!r}")
    if isinstance(value, str):
        return fill(value, token)
    if isinstance(value, dict):
        return {k: instantiate(v, token) for k, v in value.items()}
    if isinstance(value, list):
        return [instantiate(v, token) for v in value]
    return value


def load_scenario(token: str, path: Path = SCENARIO_FILE) -> dict:
    return instantiate(json.loads(path.read_text(encoding="utf-8")), token)


def source_code(scenario: dict, key: str) -> str:
    return scenario["sources"][key]["code"]


def event_id(token: str, name: str) -> str:
    """Deterministic per run token and step, so a retried request reuses the same id."""
    return str(uuid.uuid5(EVENT_NAMESPACE, f"catalogsentinel-smoke/{token}/{name}"))


def listing_event(scenario: dict, listing: dict, *, eid: str, version: int, event_type: str = UPSERTED,
                  title_suffix: str = "") -> dict:
    event = {"eventId": eid, "eventType": event_type, "schemaVersion": 1,
             "sourceCode": source_code(scenario, listing["source"]),
             "externalProductId": listing["externalProductId"], "sourceVersion": version,
             "occurredAt": scenario["occurredAt"]}
    if event_type != WITHDRAWN:
        payload = {"title": listing["title"] + title_suffix, "brand": listing.get("brand"),
                   "model": listing.get("model"), "category": listing.get("category"),
                   "gtin": listing.get("gtin"), "attributes": listing.get("attributes") or {}}
        event["payload"] = {k: v for k, v in payload.items() if v is not None}
    return event


def write_batch_export(scenario: dict, directory: Path) -> Path:
    """Writes the scenario's batch export (JSONL + manifest with checksums) and returns the manifest path."""
    batch = scenario["batch"]
    directory.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(r, separators=(",", ":")) + "\n" for r in batch["records"])
    name = "listings-00001.jsonl"
    (directory / name).write_text(text, encoding="utf-8")
    manifest = {"manifestVersion": 1, "sourceCode": source_code(scenario, batch["source"]),
                "exportId": batch["exportId"], "exportedAt": batch["exportedAt"], "format": "jsonl",
                "syntheticData": True,
                "files": [{"path": name, "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                           "records": len(batch["records"])}]}
    path = directory / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------------------------------- waiting

@dataclass
class Observation:
    done: bool
    value: Any = None
    observed: Any = None


class Deadline:
    def __init__(self, seconds: float, clock: Callable[[], float] = time.monotonic):
        self.seconds, self.clock, self.end = seconds, clock, clock() + seconds

    def remaining(self) -> float:
        return self.end - self.clock()

    def expired(self) -> bool:
        return self.remaining() <= 0


def poll(what: str, probe: Callable[[], Observation], *, timeout: float, interval: float = 2.0,
         guard: Optional[Callable[[], None]] = None, overall: Optional[Deadline] = None,
         clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], None] = time.sleep) -> Any:
    """Calls ``probe`` until it reports done; on timeout raises SmokeFailure with the last observation.
    ``guard`` runs before every probe (the dead-letter check) and may raise to fail fast."""
    deadline = Deadline(timeout, clock)
    while True:
        if guard:
            guard()
        last = probe()
        if last.done:
            return last.value
        if deadline.expired() or (overall is not None and overall.expired()):
            limit = "the run's maximum duration" if overall is not None and overall.expired() else f"{timeout:.0f} s"
            raise SmokeFailure(f"timed out after {limit} waiting for {what}", {"lastObserved": last.observed})
        sleep(max(0.05, min(interval, deadline.remaining())))


# ---------------------------------------------------------------------------------------------------- HTTP

@dataclass
class Response:
    status: int
    headers: dict
    body: Any

    def header(self, name: str) -> Optional[str]:
        return self.headers.get(name.lower())


class Api:
    def __init__(self, base: str, key: str = "", timeout: float = 15.0):
        self.base, self.key, self.timeout = base.rstrip("/"), key, timeout
        host = urllib.parse.urlparse(self.base).hostname or ""
        # Talk to a local stack directly even when HTTP(S)_PROXY is set in the environment.
        handlers = [urllib.request.ProxyHandler({})] if host in ("localhost", "127.0.0.1", "::1") else []
        self.opener = urllib.request.build_opener(*handlers)

    def call(self, method: str, path: str, body: Any = None, *, headers: Optional[dict] = None,
             expect: Optional[tuple] = (200,), raw: bool = False) -> Response:
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/json")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        if self.key:
            request.add_header("X-Api-Key", self.key)
        for k, v in (headers or {}).items():
            request.add_header(k, v)
        try:
            with self.opener.open(request, timeout=self.timeout) as r:
                status, hdrs, payload = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, hdrs, payload = e.code, e.headers, e.read()
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError, OSError) as e:
            reason = getattr(e, "reason", e)
            if isinstance(reason, (socket.timeout, TimeoutError)):
                raise SmokeFailure(f"{method} {path}: no response within {self.timeout:.0f} s") from None
            raise SmokeFailure(f"{method} {path}: connection failed ({reason})") from None
        text = payload.decode("utf-8", errors="replace")
        parsed: Any = text
        if not raw and text:
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = text
        response = Response(status, {k.lower(): v for k, v in hdrs.items()}, parsed)
        if expect is not None and status not in expect:
            raise SmokeFailure(f"{method} {path}: HTTP {status}, expected {' or '.join(map(str, expect))}",
                               {"body": parsed, "correlationId": response.header("X-Correlation-Id")})
        return response

    def get(self, path: str, **kw) -> Any:
        return self.call("GET", path, **kw).body


def qs(**params: Any) -> str:
    return urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})


def depth(entry: dict) -> Optional[int]:
    if not isinstance(entry, dict) or entry.get("unavailable"):
        return None
    return int(entry.get("visible", 0)) + int(entry.get("inFlight", 0)) + int(entry.get("delayed", 0))


def dlq_growth(before: dict, after: dict) -> dict:
    """Dead-letter queues whose depth grew, as {queue: (before, after)}; unreadable queues are ignored."""
    grown = {}
    for queue, entry in after.items():
        if not queue.endswith("-dlq"):
            continue
        now, then = depth(entry), depth(before.get(queue, {}))
        if now is not None and then is not None and now > then:
            grown[queue] = (then, now)
    return grown


def prometheus_sum(text: str, metric: str, **labels: str) -> float:
    total = 0.0
    for line in text.splitlines():
        if not line.startswith(metric + "{"):
            continue
        head, _, value = line.rpartition(" ")
        if all(f'{k}="{v}"' in head for k, v in labels.items()):
            try:
                total += float(value)
            except ValueError:
                pass
    return total


# ---------------------------------------------------------------------------------------------------- run

@dataclass
class Settings:
    api: str
    api_key: str
    worker_url: str
    token: str
    batch: str
    wait_seconds: float
    batch_seconds: float
    max_seconds: float
    request_timeout: float
    keep: bool
    bucket: str


@dataclass
class Run:
    settings: Settings
    scenario: dict
    api: Api
    overall: Deadline
    checks: int = 0
    listing_ids: dict = field(default_factory=dict)
    event_ids: dict = field(default_factory=dict)
    source_ids: dict = field(default_factory=dict)
    queues_before: dict = field(default_factory=dict)
    outbox_failed_before: Optional[int] = None
    skipped: list = field(default_factory=list)

    # -- output
    def ok(self, message: str) -> None:
        self.checks += 1
        print(f"  ok   {message}", flush=True)

    @staticmethod
    def section(title: str) -> None:
        print(f"== {title}", flush=True)

    def expect(self, condition: bool, message: str, details: Any = None) -> None:
        if not condition:
            raise SmokeFailure(message, details)
        self.ok(message)

    # -- helpers
    def code(self, key: str) -> str:
        return source_code(self.scenario, key)

    def listing(self, key: str) -> dict:
        return self.scenario["listings"][key]

    def wait(self, what: str, probe: Callable[[], Observation], timeout: Optional[float] = None) -> Any:
        return poll(what, probe, timeout=timeout or self.settings.wait_seconds, guard=self.check_dead_letters,
                    overall=self.overall)

    def queues(self) -> dict:
        return self.api.get("/api/v1/metrics/processing?windowMinutes=60").get("queues", {})

    def check_dead_letters(self) -> None:
        grown = dlq_growth(self.queues_before, self.queues())
        if grown:
            peeks = {q: self.peek(q) for q in grown}
            raise SmokeFailure("messages reached a dead-letter queue during the run: "
                               + ", ".join(f"{q} {a} -> {b}" for q, (a, b) in grown.items()), peeks)

    def peek(self, queue: str) -> Any:
        try:
            body = self.api.get(f"/api/v1/admin/dead-letters?{qs(queue=queue, max=10)}")
            return [{"failureReason": m.get("attributes", {}).get("failureReason"), "body": (m.get("body") or "")[:300]}
                    for m in body.get("messages", [])]
        except SmokeFailure as e:
            return f"peek failed: {e}"

    def listing_record(self, key: str) -> dict:
        return self.api.get(f"/api/v1/listings/{self.listing_ids[key]}")["listing"]

    def product(self, key: str) -> Optional[int]:
        return self.listing_record(key).get("canonicalProductId")

    def listings_of(self, source_key: str) -> dict:
        """The source's listings by external id; fails if any external id has more than one listing."""
        items = self.api.get("/api/v1/listings?" + qs(sourceCode=self.code(source_key), limit=200))["items"]
        by_external: dict = {}
        for item in items:
            if item["externalProductId"] in by_external:
                raise SmokeFailure(f"duplicate listings for {item['externalProductId']} in {self.code(source_key)}",
                                   [i for i in items if i["externalProductId"] == item["externalProductId"]])
            by_external[item["externalProductId"]] = item
        return by_external


def preflight(run: Run) -> None:
    run.section(f"preflight ({run.settings.api}, run token {run.settings.token})")

    def ready() -> Observation:
        try:
            r = run.api.call("GET", "/actuator/health/readiness", expect=None)
            if r.status in (401, 403, 404):
                # Behind the AWS load balancer /actuator/* is not routed (fixed 403): an authenticated read proves
                # the API and its database instead.
                r = run.api.call("GET", "/api/v1/dashboard/summary", expect=None)
        except SmokeFailure as e:
            return Observation(False, observed=str(e))
        return Observation(r.status == 200, observed={"status": r.status, "body": r.body})

    poll("API readiness", ready, timeout=run.settings.wait_seconds, overall=run.overall)
    run.ok("API ready")
    processing = run.api.get("/api/v1/metrics/processing?windowMinutes=60")
    run.queues_before = processing.get("queues", {})
    unreadable = [q for q, e in run.queues_before.items() if depth(e) is None]
    run.expect(not unreadable, "queue depths readable through /api/v1/metrics/processing",
               {"unavailable": unreadable})
    run.outbox_failed_before = int((processing.get("outbox") or {}).get("failed", 0))
    run.ok(f"baseline: dead-letter depths {({q: depth(e) for q, e in run.queues_before.items() if q.endswith('-dlq')})}, "
           f"failed outbox rows {run.outbox_failed_before}")


def register_sources(run: Run) -> None:
    run.section("sources")
    for key, source in run.scenario["sources"].items():
        r = run.api.call("POST", "/api/v1/sources", {"code": source["code"], "name": source["name"],
                                                     "role": source["role"]}, expect=(201,))
        run.source_ids[key] = r.body["sourceId"]
        run.ok(f"registered {source['role']} {source['code']} (id {r.body['sourceId']})")
    again = run.api.call("POST", "/api/v1/sources", {"code": run.code("market"), "name": "again",
                                                      "role": "MARKETPLACE"}, expect=(409,))
    run.expect(again.body.get("code") == "SOURCE_CODE_TAKEN", "registering a taken code is 409 SOURCE_CODE_TAKEN",
               again.body)


def lifecycle(run: Run) -> None:
    run.section("ingestion semantics (synchronous API)")
    spec = run.scenario["lifecycle"]
    listing = run.listing(spec["listing"])
    sent: dict = {}
    token = run.settings.token
    listing_id = None
    for step in spec["steps"]:
        name, expect = step["name"], step["expect"]
        if "replayOf" in step:
            event = sent[step["replayOf"]]
        else:
            reused = sent.get(step.get("reuseEventIdOf", ""))
            eid = reused["eventId"] if reused else event_id(token, f"lifecycle/{name}")
            version = step.get("version", reused["sourceVersion"] if reused else None)
            event = listing_event(run.scenario, listing, eid=eid, version=version,
                                  event_type=step.get("eventType", UPSERTED), title_suffix=step.get("titleSuffix", ""))
        sent[name] = event
        r = run.api.call("POST", "/api/v1/listing-events", event, expect=(expect["status"],))
        body = r.body
        problems = []
        if "code" in expect and body.get("code") != expect["code"]:
            problems.append(f"code {body.get('code')} != {expect['code']}")
        if "outcome" in expect and body.get("outcome") != expect["outcome"]:
            problems.append(f"outcome {body.get('outcome')} != {expect['outcome']}")
        if "appliedVersion" in expect and body.get("appliedVersion") != expect["appliedVersion"]:
            problems.append(f"appliedVersion {body.get('appliedVersion')} != {expect['appliedVersion']}")
        if "duplicate" in expect and body.get("duplicate") is not expect["duplicate"]:
            problems.append(f"duplicate {body.get('duplicate')} != {expect['duplicate']}")
        if expect.get("replayHeader") and (r.header("Idempotent-Replay") or "").lower() != "true":
            problems.append("missing Idempotent-Replay: true")
        if expect["status"] == 201 and not (r.header("Location") or "").startswith("/api/v1/listings/"):
            problems.append(f"Location header {r.header('Location')!r}")
        if problems:
            raise SmokeFailure(f"lifecycle step '{name}': " + "; ".join(problems), body)
        if body.get("listingId") is not None:
            if listing_id is not None and body["listingId"] != listing_id:
                raise SmokeFailure(f"lifecycle step '{name}' touched listing {body['listingId']}, not {listing_id}")
            listing_id = body["listingId"]
        run.ok(f"{name}: HTTP {r.status} {expect.get('code') or expect.get('outcome')}")
    run.listing_ids[spec["listing"]] = listing_id
    detail = run.api.get(f"/api/v1/listings/{listing_id}")
    final = spec["finalState"]
    run.expect(detail["listing"]["status"] == final["status"] and detail["listing"]["sourceVersion"] == final["sourceVersion"],
               f"listing {listing_id} ends {final['status']} at version {final['sourceVersion']}", detail["listing"])
    outcomes = sorted(e["outcome"] for e in detail["history"])
    run.expect(outcomes == spec["ledgerOutcomes"], f"ledger holds exactly {spec['ledgerOutcomes']}", outcomes)


def ingest_catalog(run: Run) -> None:
    run.section("catalog: reference via the synchronous API, marketplace via the queue (with a redelivery)")
    token = run.settings.token
    queued, redeliver = [], []
    for key, listing in run.scenario["listings"].items():
        if listing["path"] == "lifecycle":
            continue
        eid = event_id(token, f"catalog/{key}")
        run.event_ids[key] = eid
        event = listing_event(run.scenario, listing, eid=eid, version=listing["sourceVersion"])
        if listing["path"] == "sync":
            r = run.api.call("POST", "/api/v1/listing-events", event, expect=(201,))
            run.listing_ids[key] = r.body["listingId"]
            run.expect(r.body["outcome"] == "CREATED", f"{key} created as listing {r.body['listingId']}", r.body)
        else:
            queued.append(event)
            if listing.get("redeliver"):
                redeliver.append(event)
    r = run.api.call("POST", "/api/v1/ingestion/events", {"events": queued}, expect=(202,))
    run.expect(r.body["accepted"] == len(queued) and not r.body.get("failed"),
               f"queued {len(queued)} marketplace events (202)", r.body)
    r = run.api.call("POST", "/api/v1/ingestion/events", {"events": redeliver}, expect=(202,))
    run.expect(r.body["accepted"] == len(redeliver), f"redelivered {len(redeliver)} event(s) with the same event id", r.body)
    invalid = dict(queued[0], eventId=event_id(token, "catalog/invalid"),
                   payload=dict(queued[0]["payload"], gtin="2000000000016"))
    r = run.api.call("POST", "/api/v1/ingestion/events", {"events": [invalid]}, expect=(400,))
    fields = [e.get("field") for e in r.body.get("errors", [])]
    run.expect(r.body.get("code") == "VALIDATION_FAILED" and "events[0].payload.gtin" in fields,
               "an invalid queued event is rejected with 400 VALIDATION_FAILED (events[0].payload.gtin), nothing queued",
               r.body)


def await_worker(run: Run) -> None:
    run.section("worker: queued events ingested, change events matched")
    keys = list(run.scenario["listings"])

    def all_linked() -> Observation:
        by_source = {source: run.listings_of(source) for source in {run.listing(k)["source"] for k in keys}}
        state = {}
        for key in keys:
            listing = run.listing(key)
            record = by_source[listing["source"]].get(listing["externalProductId"])
            state[key] = None if record is None else (record["status"], record.get("canonicalProductId"))
        done = all(v is not None and v[0] == "ACTIVE" and v[1] is not None for v in state.values())
        return Observation(done, (state, by_source), state)

    state, by_source = run.wait("every scenario listing to be ACTIVE and linked to a canonical product", all_linked)
    for key in keys:
        listing = run.listing(key)
        run.listing_ids.setdefault(key, by_source[listing["source"]][listing["externalProductId"]]["listingId"])
    run.ok(f"all {len(keys)} listings ingested and linked: {state}")

    def queues_drained() -> Observation:
        backlog = {k: depth(v) for k, v in run.queues().items() if not k.endswith("-dlq")}
        return Observation(all(v == 0 for v in backlog.values()), backlog, backlog)

    run.wait("the listing and change queues to drain (the redelivered copy consumed)", queues_drained)
    run.ok("listing and change queues drained")
    for key, listing in run.scenario["listings"].items():
        if not listing.get("redeliver"):
            continue
        record = run.listings_of(listing["source"])[listing["externalProductId"]]  # raises on duplicates
        history = run.api.get(f"/api/v1/listings/{record['listingId']}")["history"]
        copies = [h for h in history if h["eventId"] == run.event_ids[key]]
        run.expect(len(copies) == 1 and len(history) == 1 and record["sourceVersion"] == listing["sourceVersion"],
                   f"redelivery left one listing and one ledger entry for {key}", history)


def review_candidate(run: Run) -> int:
    run.section("match review queue")
    spec = run.scenario["expect"]["reviewCandidate"]
    reference, market = (run.listing_ids[k] for k in spec["between"])
    items = run.api.get("/api/v1/match-candidates?" + qs(listingId=market, reviewStatus="PENDING", limit=50))["items"]
    run.expect(len(items) == 1, f"exactly one PENDING candidate for {spec['between'][1]}", items)
    c = items[0]
    sides = {c["left"]["listingId"], c["right"]["listingId"]}
    run.expect(sides == {reference, market} and c["decision"] == spec["decision"],
               f"it pairs {spec['between']} with decision {spec['decision']} (score {c['score']})", c)
    return c["candidateId"]


def reconcile(run: Run, label: str) -> dict:
    key = f"smoke-{run.settings.token}-{label}"
    body = {"targetSourceCode": run.code("market"), "referenceSourceCode": run.code("reference"), "requestedBy": "smoke"}
    first = run.api.call("POST", "/api/v1/reconciliation/jobs", body, headers={"Idempotency-Key": key}, expect=(202,))
    job_id = first.body["jobId"]
    run.expect((first.header("Location") or "").endswith(f"/api/v1/reconciliation/jobs/{job_id}"),
               f"reconciliation job {job_id} recorded (202, Location)", first.body)
    again = run.api.call("POST", "/api/v1/reconciliation/jobs", body, headers={"Idempotency-Key": key}, expect=(202,))
    run.expect(again.body["jobId"] == job_id, "the same Idempotency-Key returns the same job", again.body)

    def finished() -> Observation:
        job = run.api.get(f"/api/v1/reconciliation/jobs/{job_id}")
        if job["status"] == "FAILED":
            raise SmokeFailure(f"reconciliation job {job_id} FAILED on attempt {job.get('attempt')}", job)
        return Observation(job["status"] == "SUCCEEDED", job, job)

    job = run.wait(f"reconciliation job {job_id} to succeed", finished)
    run.ok(f"job {job_id} SUCCEEDED on attempt {job['attempt']} of {job['maxAttempts']}: {job.get('stats')}")
    return job


def check_gaps_and_coverage(run: Run, section_key: str) -> dict:
    expect = run.scenario["expect"][section_key]
    target = run.source_ids["market"]
    root = run.scenario["categoryRoot"]
    gaps = run.api.get("/api/v1/gaps?" + qs(targetSourceId=target, categoryRoot=root, limit=200))["items"]
    found = {}
    for g in expect["gaps"]:
        product = run.product(g["reference"])
        hits = [x for x in gaps if x["canonicalProductId"] == product and x["status"] == g["status"]]
        problems = []
        if len(hits) != 1:
            problems.append(f"{len(hits)} {g['status']} gaps for product {product}")
        else:
            gap = hits[0]
            if "reason" in g and gap["reason"] != g["reason"]:
                problems.append(f"reason {gap['reason']}")
            if "bestCandidate" in g and gap["bestCandidateListingId"] != run.listing_ids[g["bestCandidate"]]:
                problems.append(f"best candidate {gap['bestCandidateListingId']}")
            if "resolution" in g and gap.get("resolution") != g["resolution"]:
                problems.append(f"resolution {gap.get('resolution')}")
            found[g["reference"]] = gap
        if problems:
            raise SmokeFailure(f"gap for {g['reference']} is not {g}: " + "; ".join(problems), gaps)
        run.ok(f"gap for {g['reference']}: {g['status']} "
               + " ".join(f"{k}={g[k]}" for k in ("reason", "resolution", "bestCandidate") if k in g))
    live_expected = sum(1 for g in expect["gaps"] if g["status"] != "RESOLVED")
    live = [x for x in gaps if x["status"] != "RESOLVED"]
    run.expect(len(live) == live_expected, f"exactly {live_expected} live gap(s) under '{root}'", live)
    for carried in expect["carried"]:
        product = run.product(carried)
        run.expect(not [x for x in live if x["canonicalProductId"] == product],
                   f"{carried} is carried by the marketplace: no live gap", live)
    coverage = run.api.get("/api/v1/dashboard/coverage?" + qs(targetSourceCode=run.code("market")))
    rows = [r for r in coverage["categories"] if r["category"] == root]
    actual = {k: rows[0].get(k) for k in expect["coverage"]} if len(rows) == 1 else rows
    run.expect(actual == expect["coverage"], f"coverage of '{root}' is {expect['coverage']}", actual)
    return found


def decide(run: Run, candidate_id: int, gaps: dict) -> None:
    run.section("review decisions (audited, idempotent)")
    spec = run.scenario["expect"]["decisions"]
    gap = gaps[spec["confirmGapOf"]]
    key = f"smoke-{run.settings.token}-confirm"
    body = {"decision": "CONFIRM", "reviewer": "smoke", "reason": "smoke test: worth sourcing"}
    first = run.api.call("POST", f"/api/v1/gaps/{gap['gapId']}/decisions", body, headers={"Idempotency-Key": key}).body
    result = first["result"]
    run.expect(result["changed"] and result["newStatus"] == "CONFIRMED" and result["decisionId"],
               f"gap {gap['gapId']} confirmed (decision {result['decisionId']})", first)
    again = run.api.call("POST", f"/api/v1/gaps/{gap['gapId']}/decisions", body, headers={"Idempotency-Key": key}).body
    run.expect(again["result"]["decisionId"] == result["decisionId"], "replaying the decision returns the same decision id",
               again)
    detail = run.api.get(f"/api/v1/gaps/{gap['gapId']}")
    trail = [d for d in detail["history"] if d["decisionId"] == result["decisionId"]]
    run.expect(detail["gap"]["status"] == "CONFIRMED" and len(trail) == 1 and trail[0]["decision"] == "CONFIRM"
               and trail[0]["previousStatus"] == "OPEN" and trail[0]["reviewer"],
               "the gap is CONFIRMED and its audit trail holds the decision once", detail["history"])

    key = f"smoke-{run.settings.token}-approve"
    body = {"decision": "APPROVE", "reviewer": "smoke", "reason": "smoke test: same drive"}
    first = run.api.call("POST", f"/api/v1/match-candidates/{candidate_id}/decisions", body,
                         headers={"Idempotency-Key": key}).body
    result = first["result"]
    run.expect(result["newStatus"] == "APPROVED" and result["decisionId"],
               f"candidate {candidate_id} approved (decision {result['decisionId']})", first)
    again = run.api.call("POST", f"/api/v1/match-candidates/{candidate_id}/decisions", body,
                         headers={"Idempotency-Key": key}).body
    run.expect(again["result"]["decisionId"] == result["decisionId"], "replaying the approval returns the same decision id",
               again)
    candidate = run.api.get(f"/api/v1/match-candidates/{candidate_id}")
    run.expect(candidate["reviewStatus"] == "APPROVED" and candidate.get("reviewedBy") and candidate.get("reviewedAt"),
               f"candidate {candidate_id} is persisted APPROVED by {candidate.get('reviewedBy')}", candidate)
    a, b = spec["approveCandidate"]
    run.expect(run.product(a) == run.product(b), f"approval linked {a} and {b} to one canonical product",
               {a: run.product(a), b: run.product(b)})


def check_convergence(run: Run) -> None:
    for keys in run.scenario["expect"]["sameProduct"]:
        products = {k: run.product(k) for k in keys}
        run.expect(len(set(products.values())) == 1, f"{' and '.join(keys)} converge on canonical product "
                   f"{next(iter(products.values()))}", products)
        product = run.api.get(f"/api/v1/canonical-products/{next(iter(products.values()))}")
        linked = {x["listingId"] for x in product["listings"]}
        run.expect({run.listing_ids[k] for k in keys} <= linked, "the canonical product lists both listings",
                   product["listings"])


def check_queries(run: Run) -> None:
    run.section("filtered queries and aggregates over the fixtures")
    target, root = run.source_ids["market"], run.scenario["categoryRoot"]
    confirmed = run.api.get("/api/v1/gaps?" + qs(targetSourceId=target, status="CONFIRMED", reason="NO_CANDIDATE",
                                                 categoryRoot=root, limit=50))["items"]
    lantern = run.product(run.scenario["expect"]["decisions"]["confirmGapOf"])
    run.expect([g["canonicalProductId"] for g in confirmed] == [lantern],
               "gaps filtered by status, reason and category root return exactly the confirmed gap", confirmed)
    opened = run.api.get("/api/v1/gaps?" + qs(targetSourceId=target, status="OPEN", categoryRoot=root, limit=50))["items"]
    run.expect(opened == [], "no OPEN gap is left under the run's category", opened)
    for key, expected in (("reference", 3), ("market", 3)):
        items = run.api.get("/api/v1/listings?" + qs(sourceCode=run.code(key), status="ACTIVE", limit=200))["items"]
        run.expect(len(items) == expected, f"{expected} ACTIVE listings in {run.code(key)}",
                   [i["externalProductId"] for i in items])
    linked = run.api.get("/api/v1/listings?" + qs(sourceCode=run.code("market"), linked="true", limit=200))["items"]
    run.expect(len(linked) == 3, "3 linked listings in the marketplace", [i["externalProductId"] for i in linked])
    summary = run.api.get("/api/v1/dashboard/summary")
    run.expect(all(k in summary for k in ("listingsByStatus", "gapsByStatus", "lastReconciliation")),
               "dashboard summary responds with its aggregates", list(summary))
    processing = run.api.get("/api/v1/metrics/processing?windowMinutes=60")
    run.expect(any(p.get("events", 0) > 0 for p in processing.get("throughput", [])),
               "processing metrics show this run's events", processing.get("throughput"))


def compose(*args: str, stdin: Optional[str] = None, timeout: float) -> subprocess.CompletedProcess:
    cmd = ["docker", "compose", "--profile", "batch", *args]
    try:
        return subprocess.run(cmd, cwd=REPO, input=stdin, text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        raise SmokeFailure(f"`{' '.join(cmd[:6])} …` did not finish within {timeout:.0f} s",
                           {"stdout": (e.stdout or "")[-2000:] if isinstance(e.stdout, str) else None,
                            "stderr": (e.stderr or "")[-2000:] if isinstance(e.stderr, str) else None}) from None


def last_json_object(text: str) -> dict:
    decoder = json.JSONDecoder()
    for i in reversed([m.start() for m in re.finditer(r"(?m)^\{", text)]):
        try:
            value, _ = decoder.raw_decode(text[i:])
            if isinstance(value, dict):
                return value
        except ValueError:
            continue
    raise ValueError("no JSON object in output")


STAGE_SCRIPT = """
import os, sys, boto3
bucket, prefix = sys.argv[1], sys.argv[2]
s3 = boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT") or None, region_name=os.environ["AWS_REGION"])
if bucket not in [b["Name"] for b in s3.list_buckets().get("Buckets", [])]:
    s3.create_bucket(Bucket=bucket)
for name in sorted(os.listdir("/data")):
    s3.upload_file(f"/data/{name}", bucket, prefix + name)
print("staged", len(os.listdir("/data")))
"""


def batch(run: Run) -> None:
    run.section("batch: export -> PySpark job -> queue -> worker")
    s, spec = run.settings, run.scenario["batch"]
    expect = spec["expect"]
    directory = REPO / "build" / "smoke" / s.token / "export"
    write_batch_export(run.scenario, directory)
    prefix = f"incoming/{run.code('batch')}/{spec['exportId']}/"
    staged = compose("run", "--rm", "-T", "-v", f"{directory}:/data:ro", "--entrypoint", "python", "batch", "-",
                     s.bucket, prefix, stdin=STAGE_SCRIPT, timeout=s.batch_seconds)
    run.expect(staged.returncode == 0, f"export staged in s3://{s.bucket}/{prefix}",
               {"stdout": staged.stdout[-2000:], "stderr": staged.stderr[-2000:]})
    args = ["run", "--rm", "-T"]
    if s.api_key:
        args += ["-e", f"CATALOGSENTINEL_API_KEY={s.api_key}"]
    args += ["batch", "run", "--manifest", f"s3://{s.bucket}/{prefix}manifest.json",
             "--output", f"s3://{s.bucket}/curated/listings", "--rejected", f"s3://{s.bucket}/rejected",
             "--state", f"s3://{s.bucket}/_state"]

    def run_batch() -> dict:
        p = compose(*args, timeout=s.batch_seconds)
        try:
            result = last_json_object(p.stdout)
        except ValueError:
            raise SmokeFailure(f"batch job exited {p.returncode} without a result", {"stdout": p.stdout[-3000:],
                                                                                   "stderr": p.stderr[-3000:]}) from None
        if p.returncode != 0:
            raise SmokeFailure(f"batch job exited {p.returncode}", {"result": result, "stderr": p.stderr[-3000:]})
        return result

    result = run_batch()
    counts = {k: result.get(k) for k in ("rows_total", "rows_valid", "rows_rejected", "events_published")}
    wanted = {"rows_total": expect["rowsTotal"], "rows_valid": expect["rowsValid"],
              "rows_rejected": expect["rowsRejected"], "events_published": expect["eventsPublished"]}
    run.expect(result.get("status") == "PUBLISHED" and counts == wanted,
               f"batch job {result.get('job_id')} PUBLISHED with {counts}", result)
    job_id = result["job_id"]

    def processed() -> Observation:
        view = run.api.get(f"/api/v1/ingestion/jobs/{job_id}")
        return Observation(bool(view["progress"]["fullyProcessed"]), view, view["progress"])

    view = run.wait(f"batch job {job_id} to be fully processed by the worker", processed)
    progress = view["progress"]
    run.expect(view["job"]["status"] == "PUBLISHED" and progress["processedEvents"] == expect["eventsPublished"]
               and progress["byOutcome"] == expect["processedByOutcome"],
               f"job {job_id}: PUBLISHED, fullyProcessed, {progress['processedEvents']} events {progress['byOutcome']}",
               view)

    def linked() -> Observation:
        items = run.api.get("/api/v1/listings?" + qs(sourceCode=run.code("batch"), status="ACTIVE", linked="true",
                                                     limit=200))["items"]
        return Observation(len(items) == expect["activeListings"], items, [i["externalProductId"] for i in items])

    run.wait("the batch listings to be matched", linked)
    run.ok(f"{expect['activeListings']} batch listings ACTIVE and linked; the invalid record was rejected")
    rerun = run_batch()
    run.expect(rerun.get("skipped") is True and rerun.get("job_id") == job_id,
               "re-running the same manifest is a no-op (same job, skipped)", rerun)
    after = run.api.get(f"/api/v1/ingestion/jobs/{job_id}")["progress"]
    run.expect(after["processedEvents"] == expect["eventsPublished"], "no events were processed twice", after)


def final_checks(run: Run) -> None:
    run.section("dead letters and outbox")
    run.check_dead_letters()
    run.ok("no message reached a dead-letter queue during the run")
    outbox = run.api.get("/api/v1/metrics/processing?windowMinutes=60").get("outbox") or {}
    failed = int(outbox.get("failed", 0))
    run.expect(failed <= (run.outbox_failed_before or 0), f"no outbox row failed during the run ({failed} failed)",
               outbox)


def diagnostics(run: Optional[Run]) -> dict:
    if run is None:
        return {}
    out: dict = {}
    api = Api(run.settings.api, run.settings.api_key, timeout=5)

    def attempt(name: str, fn: Callable[[], Any]) -> None:
        try:
            out[name] = fn()
        except Exception as e:  # diagnostics are best effort
            out[name] = f"unavailable: {e}"

    attempt("queuesAndOutbox", lambda: {k: v for k, v in api.get("/api/v1/metrics/processing?windowMinutes=60").items()
                                        if k in ("queues", "outbox")})
    for queue in [q for q in run.queues_before if q.endswith("-dlq")]:
        attempt(f"deadLetters:{queue}", lambda q=queue: run.peek(q))
    if "market" in run.source_ids:
        attempt("reconciliationJobs", lambda: api.get(
            "/api/v1/reconciliation/jobs?" + qs(targetSourceId=run.source_ids["market"], limit=3))["items"])
    for key in ("reference", "market", "batch"):
        if key in run.source_ids:
            attempt(f"listings:{key}", lambda k=key: [
                {k2: i.get(k2) for k2 in ("listingId", "externalProductId", "status", "sourceVersion", "canonicalProductId")}
                for i in api.get("/api/v1/listings?" + qs(sourceCode=run.code(k), limit=50))["items"]])
    return out


def cleanup(run: Run) -> None:
    if run.settings.keep:
        print(f"-- kept the run's sources enabled (--keep): {sorted(run.source_ids)}")
        return
    for key, source_id in run.source_ids.items():
        try:
            run.api.call("PATCH", f"/api/v1/sources/{source_id}", {"enabled": False})
            print(f"-- disabled {run.code(key)}")
        except SmokeFailure as e:
            print(f"-- could not disable {run.code(key)}: {e}", file=sys.stderr)


def parse_args(argv: Optional[list] = None) -> Settings:
    env = os.environ
    p = argparse.ArgumentParser(description="End-to-end smoke test against a running CatalogSentinel stack.")
    p.add_argument("--api", default=env.get("API", "http://localhost:8080"), help="API base URL (API)")
    p.add_argument("--api-key", default=env.get("API_KEY", ""), help="ADMIN API key when security is enabled (API_KEY)")
    p.add_argument("--worker-url", default=env.get("WORKER_URL", "http://localhost:8081"),
                   help="worker actuator, for the duplicate-delivery counter (WORKER_URL)")
    p.add_argument("--token", default=env.get("SMOKE_TOKEN") or new_token(),
                   help="run token, 4-24 lowercase letters, fresh per run (SMOKE_TOKEN; random by default)")
    p.add_argument("--batch", choices=["compose", "skip"], default=env.get("SMOKE_BATCH", "compose"),
                   help="run the batch section with docker compose, or skip it explicitly (SMOKE_BATCH)")
    p.add_argument("--wait-seconds", type=float, default=float(env.get("SMOKE_WAIT_SECONDS", 180)),
                   help="deadline for each asynchronous wait (SMOKE_WAIT_SECONDS)")
    p.add_argument("--batch-seconds", type=float, default=float(env.get("SMOKE_BATCH_SECONDS", 600)),
                   help="deadline for each batch container run (SMOKE_BATCH_SECONDS)")
    p.add_argument("--max-seconds", type=float, default=float(env.get("SMOKE_MAX_SECONDS", 1200)),
                   help="hard limit for the whole run (SMOKE_MAX_SECONDS)")
    p.add_argument("--request-timeout", type=float, default=float(env.get("SMOKE_REQUEST_TIMEOUT", 15)),
                   help="timeout of each HTTP request (SMOKE_REQUEST_TIMEOUT)")
    p.add_argument("--keep", action="store_true", default=env.get("SMOKE_KEEP") == "1",
                   help="leave the run's sources enabled for inspection (SMOKE_KEEP=1)")
    p.add_argument("--bucket", default=env.get("BATCH_BUCKET", "catalogsentinel-batch"), help="S3 bucket (BATCH_BUCKET)")
    a = p.parse_args(argv)
    if not TOKEN_RE.fullmatch(a.token):
        raise UsageError(f"--token must be 4-24 lowercase letters, got {a.token!r}")
    for name in ("wait_seconds", "batch_seconds", "max_seconds", "request_timeout"):
        if getattr(a, name) <= 0:
            raise UsageError(f"--{name.replace('_', '-')} must be positive")
    return Settings(a.api, a.api_key, a.worker_url.rstrip("/"), a.token, a.batch, a.wait_seconds, a.batch_seconds,
                    a.max_seconds, a.request_timeout, a.keep, a.bucket)


def worker_duplicates(settings: Settings) -> Optional[float]:
    try:
        text = Api(settings.worker_url, timeout=5).call("GET", "/actuator/prometheus", raw=True).body
    except SmokeFailure:
        return None
    return prometheus_sum(text, "catalog_ingestion_events_total", duplicate="true")


def main(argv: Optional[list] = None) -> int:
    started = time.monotonic()
    run: Optional[Run] = None
    try:
        settings = parse_args(argv)
        if settings.batch == "compose":
            probe = subprocess.run(["docker", "compose", "version"], capture_output=True, text=True)
            if probe.returncode != 0:
                raise UsageError("docker compose is needed for the batch section; run from the repository with the "
                                 "stack up, or pass --batch skip to skip that section explicitly")

        def out_of_time(signum, frame):
            raise SmokeFailure(f"the run exceeded its maximum duration of {settings.max_seconds:.0f} s")

        signal.signal(signal.SIGALRM, out_of_time)
        signal.alarm(int(settings.max_seconds) + 1)
        run = Run(settings, load_scenario(settings.token), Api(settings.api, settings.api_key, settings.request_timeout),
                  Deadline(settings.max_seconds))
        print(f"Smoke test against {settings.api}: run token {settings.token} (all data synthetic)", flush=True)
        try:
            preflight(run)
            duplicates_before = worker_duplicates(settings)
            register_sources(run)
            lifecycle(run)
            ingest_catalog(run)
            await_worker(run)
            duplicates_after = worker_duplicates(settings)
            if duplicates_before is not None and duplicates_after is not None:
                run.expect(duplicates_after >= duplicates_before + 1,
                           f"the worker counted the redelivery as a duplicate "
                           f"(catalog_ingestion_events_total{{duplicate=\"true\"}} +{duplicates_after - duplicates_before:.0f})")
            else:
                run.skipped.append(f"worker duplicate counter (worker metrics not reachable at {settings.worker_url})")
            candidate = review_candidate(run)
            run.section("reconciliation 1 (scoped to the run's reference source)")
            reconcile(run, "reconcile-1")
            check_convergence(run)
            gaps = check_gaps_and_coverage(run, "firstReconciliation")
            decide(run, candidate, gaps)
            run.section("reconciliation 2 (after the decisions)")
            reconcile(run, "reconcile-2")
            check_gaps_and_coverage(run, "secondReconciliation")
            check_queries(run)
            if settings.batch == "compose":
                batch(run)
            else:
                run.skipped.append("batch section (--batch skip)")
            final_checks(run)
        finally:
            signal.alarm(0)
            cleanup(run)
    except UsageError as e:
        print(f"smoke: {e}", file=sys.stderr)
        return 2
    except SmokeFailure as e:
        print(f"  FAIL {e}", file=sys.stderr, flush=True)
        if e.details is not None:
            print("       details: " + json.dumps(e.details, default=str)[:6000], file=sys.stderr)
        print("       diagnostics: " + json.dumps(diagnostics(run), default=str)[:12000], file=sys.stderr)
        print(f"Smoke test FAILED after {time.monotonic() - started:.0f} s"
              + (f" ({run.checks} checks passed before the failure)" if run else ""), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    elapsed = time.monotonic() - started
    for item in run.skipped:
        print(f"  SKIPPED {item}")
    print(f"Smoke test passed: {run.checks} checks in {elapsed:.0f} s (run token {run.settings.token})"
          + (f", {len(run.skipped)} item(s) skipped" if run.skipped else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
