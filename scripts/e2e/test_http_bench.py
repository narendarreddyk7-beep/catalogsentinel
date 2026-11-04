"""Unit tests for scripts/e2e/http_bench.py: statistics and the load loop against a local stub server."""
from __future__ import annotations

import http.server
import json
import threading

import pytest

import http_bench


def test_nearest_rank_percentiles():
    values = list(range(1, 101))  # 1..100
    assert http_bench.percentile(values, 50) == 50
    assert http_bench.percentile(values, 95) == 95
    assert http_bench.percentile(values, 99) == 99
    assert http_bench.percentile(values, 100) == 100
    assert http_bench.percentile([7.0], 99) == 7.0


def test_summary_counts_errors_in_requests_and_throughput():
    s = http_bench.summarize("x", [1.0, 2.0, 3.0], errors=1, seconds=2.0)
    assert s["requests"] == 4 and s["errors"] == 1 and s["throughput"] == 2.0
    assert s["p50"] == 2.0 and s["max"] == 3.0
    assert http_bench.row(s).startswith("| x | 4 | 1 | 2 req/s | 2.0 ms |")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # keep-alive, as the client expects

    def log_message(self, *args):
        pass

    def do_GET(self):
        status = 503 if self.path.endswith("/fail") else 200
        body = json.dumps({"ok": status == 200}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def server():
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_load_measures_successes_and_counts_failures(server):
    ok = http_bench.load(server, "", "ok", lambda i: ("GET", "/ok", None), requests=40, concurrency=4, warmup=5,
                         timeout=5)
    assert ok["requests"] == 40 and ok["errors"] == 0 and ok["p50"] > 0
    failing = http_bench.load(server, "", "fail", lambda i: ("GET", "/fail", None), requests=10, concurrency=2,
                              warmup=0, timeout=5)
    assert failing["requests"] == 10 and failing["errors"] == 10
