#!/usr/bin/env python3
"""HTTP and event-to-catalog latency against a running stack (scripts/benchmark.sh, section "http").

1. HTTP latency: for a fixed set of read endpoints and synchronous ingestion, N requests from C concurrent workers
   over persistent connections, after a warm-up; reports throughput, p50/p95/p99/max and non-2xx responses.
2. Event-to-catalog latency: K new listings are queued in one POST /api/v1/ingestion/events; the listings are
   polled until each has a canonical product (queue -> worker ingest -> outbox -> relay -> change queue -> worker
   matching). Latency per listing = first poll that sees it linked minus the accept time, so its resolution is the
   poll interval. Queue depths and the outbox's oldest pending age are sampled meanwhile (the API exposes depths,
   not the age of the oldest SQS message, so the outbox age is the queue-age figure available end to end).

Uses its own run-scoped sources (disabled at the end) and synthetic data only. Standard library only.
"""
from __future__ import annotations

import argparse
import http.client
import json
import secrets
import string
import sys
import threading
import time
import urllib.parse
import uuid
from typing import Callable, Optional

import smoke


def percentile(sorted_values: list, p: float) -> float:
    """Nearest-rank percentile of an ascending list (p in [0, 100])."""
    if not sorted_values:
        return float("nan")
    rank = max(1, min(len(sorted_values), int(-(-p * len(sorted_values) // 100))))
    return sorted_values[rank - 1]


def summarize(name: str, latencies_ms: list, errors: int, seconds: float) -> dict:
    values = sorted(latencies_ms)
    return {"name": name, "requests": len(values) + errors, "errors": errors,
            "throughput": (len(values) + errors) / seconds if seconds > 0 else float("nan"),
            "p50": percentile(values, 50), "p95": percentile(values, 95), "p99": percentile(values, 99),
            "max": values[-1] if values else float("nan")}


def row(s: dict, unit: str = "req/s") -> str:
    return (f"| {s['name']} | {s['requests']} | {s['errors']} | {s['throughput']:.0f} {unit} | {s['p50']:.1f} ms | "
            f"{s['p95']:.1f} ms | {s['p99']:.1f} ms | {s['max']:.1f} ms |")


class Client:
    """One persistent HTTP/1.1 connection (per worker thread)."""

    def __init__(self, base: str, key: str, timeout: float):
        u = urllib.parse.urlparse(base)
        self.https = u.scheme == "https"
        self.host, self.port, self.timeout, self.key = u.hostname, u.port, timeout, key
        self.conn: Optional[http.client.HTTPConnection] = None

    def request(self, method: str, path: str, body: Optional[bytes] = None) -> int:
        for attempt in (1, 2):  # one reconnect if the server closed an idle keep-alive connection
            if self.conn is None:
                cls = http.client.HTTPSConnection if self.https else http.client.HTTPConnection
                self.conn = cls(self.host, self.port, timeout=self.timeout)
            headers = {"Accept": "application/json"}
            if body is not None:
                headers["Content-Type"] = "application/json"
            if self.key:
                headers["X-Api-Key"] = self.key
            try:
                self.conn.request(method, path, body=body, headers=headers)
                response = self.conn.getresponse()
                response.read()
                return response.status
            except (http.client.HTTPException, OSError):
                self.conn.close()
                self.conn = None
                if attempt == 2:
                    return 0
        return 0


def load(base: str, key: str, name: str, make_request: Callable[[int], tuple], requests: int, concurrency: int,
         warmup: int, timeout: float) -> dict:
    counter = iter(range(warmup + requests))
    lock = threading.Lock()
    latencies, errors = [], [0]

    def worker():
        client = Client(base, key, timeout)
        while True:
            with lock:
                i = next(counter, None)
            if i is None:
                return
            method, path, body = make_request(i)
            t0 = time.perf_counter()
            status = client.request(method, path, body)
            elapsed = (time.perf_counter() - t0) * 1000
            if i < warmup:
                continue
            with lock:
                if 200 <= status < 300:
                    latencies.append(elapsed)
                else:
                    errors[0] += 1

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    start = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return summarize(name, latencies, errors[0], time.perf_counter() - start)


def main(argv: Optional[list] = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--api", default="http://localhost:8080")
    p.add_argument("--api-key", default="")
    p.add_argument("--requests", type=int, default=500)
    p.add_argument("--concurrency", type=int, default=8)
    p.add_argument("--warmup", type=int, default=50)
    p.add_argument("--events", type=int, default=200, help="listings for the event-to-catalog measurement")
    p.add_argument("--poll-interval", type=float, default=0.25)
    p.add_argument("--wait-seconds", type=float, default=300)
    p.add_argument("--out", default=None, help="write the raw results as JSON")
    a = p.parse_args(argv)
    api = smoke.Api(a.api, a.api_key, timeout=30)
    token = "".join(secrets.choice(string.ascii_lowercase) for _ in range(8))
    ref, mkt = f"bench-{token}-ref", f"bench-{token}-mkt"
    source_ids = []
    results: dict = {"api": a.api, "token": token, "http": [], "eventToCatalog": None}
    try:
        for code, role in ((ref, "REFERENCE"), (mkt, "MARKETPLACE")):
            source_ids.append(api.call("POST", "/api/v1/sources", {"code": code, "name": f"Benchmark {code} (synthetic)",
                                                                    "role": role}, expect=(201,)).body["sourceId"])
        sample = api.get("/api/v1/listings?limit=50")["items"] or [{"listingId": 1}]
        ids = [x["listingId"] for x in sample]

        def sync_event(i: int) -> tuple:
            event = {"eventId": str(uuid.uuid4()), "eventType": "LISTING_UPSERTED", "schemaVersion": 1,
                     "sourceCode": mkt, "externalProductId": f"HTTP-{i:06d}", "sourceVersion": 1,
                     "occurredAt": "2026-05-01T00:00:00Z",
                     "payload": {"title": f"Benchmark {token} item {i:06d} 500 ml", "brand": f"Bench{token}"}}
            return "POST", "/api/v1/listing-events", json.dumps(event).encode()

        cases = [
            ("GET /api/v1/listings?limit=50", lambda i: ("GET", "/api/v1/listings?limit=50", None)),
            ("GET /api/v1/listings/{id}", lambda i: ("GET", f"/api/v1/listings/{ids[i % len(ids)]}", None)),
            ("GET /api/v1/gaps?status=OPEN&limit=50", lambda i: ("GET", "/api/v1/gaps?status=OPEN&limit=50", None)),
            ("GET /api/v1/dashboard/summary", lambda i: ("GET", "/api/v1/dashboard/summary", None)),
            ("POST /api/v1/listing-events (new listing)", sync_event),
        ]
        print("| endpoint | requests | errors | throughput | p50 | p95 | p99 | max |")
        print("|---|---|---|---|---|---|---|---|")
        for name, make in cases:
            s = load(a.api, a.api_key, name, make, a.requests, a.concurrency, a.warmup, 30)
            results["http"].append(s)
            print(row(s), flush=True)

        # Event to catalog.
        events = [{"eventId": str(uuid.uuid4()), "eventType": "LISTING_UPSERTED", "schemaVersion": 1,
                   "sourceCode": ref, "externalProductId": f"E2C-{i:05d}", "sourceVersion": 1,
                   "occurredAt": "2026-05-01T00:00:00Z",
                   "payload": {"title": f"Benchmark {token} reference item {i:05d}", "brand": f"Bench{token}"}}
                  for i in range(a.events)]
        accepted_at = time.perf_counter()
        api.call("POST", "/api/v1/ingestion/events", {"events": events}, expect=(202,))
        linked_at: dict = {}
        samples = []
        deadline = accepted_at + a.wait_seconds
        last_sample = 0.0
        while len(linked_at) < a.events and time.perf_counter() < deadline:
            cursor = None
            while True:
                page = api.get("/api/v1/listings?" + smoke.qs(sourceCode=ref, linked="true", limit=200, cursor=cursor))
                now = time.perf_counter()
                for item in page["items"]:
                    linked_at.setdefault(item["externalProductId"], now)
                cursor = page["page"].get("nextCursor")
                if not page["page"].get("hasMore"):
                    break
            if time.perf_counter() - last_sample >= 1.0:
                last_sample = time.perf_counter()
                m = api.get("/api/v1/metrics/processing?windowMinutes=15")
                samples.append({"t": round(last_sample - accepted_at, 2),
                                "queues": {q: smoke.depth(e) for q, e in m.get("queues", {}).items()},
                                "outboxOldestPendingSeconds": (m.get("outbox") or {}).get("oldestPendingSeconds")})
            time.sleep(a.poll_interval)
        latencies = sorted((t - accepted_at) * 1000 for t in linked_at.values())
        s = summarize(f"event-to-catalog ({a.events} queued listings, poll {a.poll_interval * 1000:.0f} ms)",
                      latencies, a.events - len(latencies), max(latencies) / 1000 if latencies else 0)
        results["eventToCatalog"] = dict(s, samples=samples)
        print()
        print("| measurement | listings | not linked in time | throughput | p50 | p95 | p99 | max |")
        print("|---|---|---|---|---|---|---|---|")
        print(row(s, unit="listings/s"))
        max_depth = max((d or 0 for smp in samples for q, d in smp["queues"].items() if not q.endswith("-dlq")),
                        default=0)
        max_age = max((smp["outboxOldestPendingSeconds"] or 0 for smp in samples), default=0)
        print(f"\nWhile they were processed: deepest queue {max_depth} messages, oldest pending outbox row "
              f"{max_age:.1f} s (sampled every second).")
        return 0 if len(latencies) == a.events else 1
    except smoke.SmokeFailure as e:
        print(f"http_bench FAILED: {e} {json.dumps(e.details, default=str)[:2000] if e.details else ''}",
              file=sys.stderr)
        return 1
    finally:
        for sid in source_ids:
            try:
                api.call("PATCH", f"/api/v1/sources/{sid}", {"enabled": False})
            except smoke.SmokeFailure:
                pass
        if a.out:
            with open(a.out, "w", encoding="utf-8") as f:
                json.dump(results, f, indent=2, default=str)


if __name__ == "__main__":
    sys.exit(main())
