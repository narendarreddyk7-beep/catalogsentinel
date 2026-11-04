"""Unit tests for scripts/e2e/smoke.py: the parts that can be checked without a running stack.

The scenario's expected outcomes are checked against the real services by SmokeScenarioIT (Java) and the batch
export by jobs/catalog-batch/tests/test_smoke_export.py; these tests cover the script's own logic: fixture
instantiation (identical to the Java side), event building, deadlines, HTTP failure handling and the dead-letter
check. Run: python -m pytest scripts/e2e
"""
from __future__ import annotations

import hashlib
import http.server
import json
import threading
import time
from pathlib import Path

import pytest

import smoke


# ------------------------------------------------------------------------------------------------ fixtures

def test_gtin_derivation_matches_the_java_implementation():
    # Same vectors as SmokeScenarioIT.gtinDerivationMatchesThePythonImplementation.
    assert smoke.gtin_for("qorvandel", "kettle") == "2264317892033"
    assert smoke.gtin_for("zymbrastik", "drive") == "2138650499429"
    assert smoke.gtin_for("plenturix", "bottle") == "2971467712868"
    assert smoke.gs1_check_digit("200000000001") == 5


def test_fill_matches_the_java_implementation():
    assert smoke.fill("{Brand} {BRAND} {root} {token}", "abcd") == "Abcd ABCD smoke abcd abcd"


def test_instantiated_scenario_has_no_placeholders_and_valid_codes():
    scenario = smoke.load_scenario("qorvandel")
    text = json.dumps({k: v for k, v in scenario.items() if k != "description"})
    assert "{token}" not in text and "{gtin:" not in text and "{Brand}" not in text and "{root}" not in text
    for source in scenario["sources"].values():
        assert len(source["code"]) <= 64 and source["code"].startswith("smk-qorvandel-")
    assert scenario["categoryRoot"] == "smoke qorvandel"
    assert scenario["listings"]["refKettle"]["gtin"] == scenario["listings"]["mktKettle"]["gtin"] == "2264317892033"


def test_tokens_are_validated():
    with pytest.raises(smoke.UsageError):
        smoke.load_scenario("Bad-Token")
    with pytest.raises(smoke.UsageError):
        smoke.parse_args(["--token", "abc"])
    assert smoke.TOKEN_RE.fullmatch(smoke.new_token())


def test_events_omit_missing_fields_and_withdrawals_carry_no_payload():
    scenario = smoke.load_scenario("qorvandel")
    drive = scenario["listings"]["mktDrive"]
    upsert = smoke.listing_event(scenario, drive, eid="e1", version=1)
    assert upsert["sourceCode"] == "smk-qorvandel-mkt" and upsert["sourceVersion"] == 1
    assert "model" not in upsert["payload"] and "gtin" not in upsert["payload"]
    assert upsert["payload"]["title"] == "Qorvandel Pro USB Flash Drive 64GB Black"
    withdraw = smoke.listing_event(scenario, drive, eid="e2", version=3, event_type=smoke.WITHDRAWN)
    assert "payload" not in withdraw and withdraw["eventType"] == "LISTING_WITHDRAWN"
    edited = smoke.listing_event(scenario, drive, eid="e3", version=2, title_suffix=" (edited)")
    assert edited["payload"]["title"].endswith(" (edited)")


def test_event_ids_are_deterministic_per_token_and_step():
    assert smoke.event_id("abcd", "x") == smoke.event_id("abcd", "x")
    assert smoke.event_id("abcd", "x") != smoke.event_id("abce", "x")
    assert smoke.event_id("abcd", "x") != smoke.event_id("abcd", "y")


def test_batch_export_has_a_consistent_manifest(tmp_path: Path):
    scenario = smoke.load_scenario("qorvandel")
    manifest_path = smoke.write_batch_export(scenario, tmp_path)
    manifest = json.loads(manifest_path.read_text())
    entry = manifest["files"][0]
    data = (tmp_path / entry["path"]).read_bytes()
    assert hashlib.sha256(data).hexdigest() == entry["sha256"]
    assert entry["records"] == len(data.decode().splitlines()) == 4
    assert manifest["sourceCode"] == "smk-qorvandel-bat" and manifest["syntheticData"] is True


def test_lifecycle_ledger_expectation_matches_its_steps():
    spec = smoke.load_scenario("qorvandel")["lifecycle"]
    recorded = sorted(s["expect"].get("code") or s["expect"]["outcome"] for s in spec["steps"]
                      if s["expect"].get("code") != "EVENT_ID_REUSED" and "replayOf" not in s)
    assert recorded == spec["ledgerOutcomes"]


# ------------------------------------------------------------------------------------------------ waiting

class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


def test_poll_returns_once_the_probe_is_done():
    clock = FakeClock()
    answers = iter([smoke.Observation(False, observed=1), smoke.Observation(True, value="v")])
    assert smoke.poll("x", lambda: next(answers), timeout=10, clock=clock, sleep=clock.sleep) == "v"


def test_poll_times_out_with_the_last_observation():
    clock = FakeClock()
    with pytest.raises(smoke.SmokeFailure) as e:
        smoke.poll("the thing", lambda: smoke.Observation(False, observed={"n": clock.now}), timeout=5, interval=2,
                   clock=clock, sleep=clock.sleep)
    assert "timed out after 5 s waiting for the thing" in str(e.value)
    assert e.value.details["lastObserved"]["n"] >= 5


def test_poll_guard_fails_fast():
    def guard():
        raise smoke.SmokeFailure("dead letters")

    with pytest.raises(smoke.SmokeFailure, match="dead letters"):
        smoke.poll("x", lambda: smoke.Observation(True), timeout=5, guard=guard)


def test_poll_respects_the_overall_deadline():
    clock = FakeClock()
    overall = smoke.Deadline(1, clock)
    with pytest.raises(smoke.SmokeFailure, match="maximum duration"):
        smoke.poll("x", lambda: smoke.Observation(False), timeout=100, interval=1, overall=overall, clock=clock,
                   sleep=clock.sleep)


# ------------------------------------------------------------------------------------------------ queues

def test_dead_letter_growth_is_detected_and_unreadable_queues_are_ignored():
    before = {"q": {"visible": 5}, "q-dlq": {"visible": 1, "inFlight": 0, "delayed": 0}, "x-dlq": {"unavailable": True}}
    same = {"q": {"visible": 0}, "q-dlq": {"visible": 0, "inFlight": 1, "delayed": 0}, "x-dlq": {"visible": 9}}
    assert smoke.dlq_growth(before, same) == {}
    grown = {"q": {"visible": 0}, "q-dlq": {"visible": 2, "inFlight": 0, "delayed": 0}}
    assert smoke.dlq_growth(before, grown) == {"q-dlq": (1, 2)}


def test_prometheus_sum_filters_by_labels():
    text = "\n".join([
        "# HELP catalog_ingestion_events_total x",
        'catalog_ingestion_events_total{duplicate="true",outcome="CREATED",path="ASYNC_QUEUE"} 2.0',
        'catalog_ingestion_events_total{duplicate="false",outcome="CREATED",path="ASYNC_QUEUE"} 7.0',
        'catalog_ingestion_events_total{duplicate="true",outcome="UPDATED",path="BATCH"} 1.0',
    ])
    assert smoke.prometheus_sum(text, "catalog_ingestion_events_total", duplicate="true") == 3.0
    assert smoke.prometheus_sum(text, "catalog_ingestion_events_total", duplicate="true", path="BATCH") == 1.0


def test_last_json_object_skips_log_noise():
    out = 'WARN something {not json}\n{\n  "job_id": 7,\n  "status": "PUBLISHED"\n}\n'
    assert smoke.last_json_object(out) == {"job_id": 7, "status": "PUBLISHED"}
    with pytest.raises(ValueError):
        smoke.last_json_object("no json here")


# ------------------------------------------------------------------------------------------------ HTTP

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/slow":
            time.sleep(2)
        if self.path == "/conflict":
            body = json.dumps({"code": "VERSION_CONFLICT", "status": 409}).encode()
            self.send_response(409)
        else:
            body = json.dumps({"ok": True, "key": self.headers.get("X-Api-Key")}).encode()
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Correlation-Id", "c-1")
        self.end_headers()
        self.wfile.write(body)


@pytest.fixture()
def server():
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def test_api_parses_json_and_sends_the_key(server):
    r = smoke.Api(server, "k-1").call("GET", "/ok")
    assert r.status == 200 and r.body == {"ok": True, "key": "k-1"} and r.header("x-correlation-id") == "c-1"


def test_unexpected_status_fails_with_body_and_correlation_id(server):
    with pytest.raises(smoke.SmokeFailure) as e:
        smoke.Api(server).call("GET", "/conflict")
    assert "HTTP 409, expected 200" in str(e.value)
    assert e.value.details == {"body": {"code": "VERSION_CONFLICT", "status": 409}, "correlationId": "c-1"}
    assert smoke.Api(server).call("GET", "/conflict", expect=(409,)).body["code"] == "VERSION_CONFLICT"


def test_request_timeout_and_refused_connections_fail_clearly(server):
    with pytest.raises(smoke.SmokeFailure, match="no response within 1 s"):
        smoke.Api(server, timeout=1).call("GET", "/slow")
    with pytest.raises(smoke.SmokeFailure, match="connection failed"):
        smoke.Api("http://127.0.0.1:9", timeout=1).call("GET", "/")


def test_an_unreachable_api_fails_the_run_with_exit_code_1_within_its_deadline(capsys):
    started = time.monotonic()
    code = smoke.main(["--api", "http://127.0.0.1:9", "--batch", "skip", "--wait-seconds", "2",
                       "--request-timeout", "1", "--token", "abcdef"])
    assert code == 1 and time.monotonic() - started < 15
    err = capsys.readouterr().err
    assert "FAIL timed out after 2 s waiting for API readiness" in err and "diagnostics" in err


def test_usage_errors_exit_2(capsys):
    assert smoke.main(["--token", "NO", "--batch", "skip"]) == 2
    assert smoke.main(["--wait-seconds", "0", "--batch", "skip"]) == 2
