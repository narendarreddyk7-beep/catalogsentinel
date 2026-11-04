"""Unit tests for scripts/e2e/drills.py that need no running stack. The batch-crash drill's export is run through
the real pipeline, crash included, by jobs/catalog-batch/tests/test_smoke_export.py."""
from __future__ import annotations

import json
import subprocess
import time

import pytest

import drills


def test_job_key_matches_the_batch_rule():
    manifest = {"manifestVersion": 1, "sourceCode": "contoso-wholesale", "exportId": "x", "files": []}
    key = drills.derive_job_key(manifest)
    assert key.startswith("contoso-wholesale:") and len(key.split(":")[1]) == 24
    assert drills.derive_job_key(dict(reversed(list(manifest.items())))) == key  # key order does not matter


def test_drill_export_is_consistent(tmp_path):
    manifest = drills.write_drill_export(tmp_path, "contoso-wholesale", "drill-batch-crash-1", "DRILL-x", 25)
    lines = (tmp_path / "listings-00001.jsonl").read_text().splitlines()
    assert len(lines) == manifest["files"][0]["records"] == 25
    assert len({json.loads(line)["externalProductId"] for line in lines}) == 25
    assert json.loads((tmp_path / "manifest.json").read_text()) == manifest


def test_unknown_scenarios_and_bad_limits_exit_2(capsys):
    with pytest.raises(SystemExit) as e:
        drills.parse_args(["meteor-strike"])
    assert e.value.code == 2
    assert drills.main(["db-outage", "--wait-seconds", "0"]) == 2


def fake_run(compose_ok: bool):
    real = subprocess.run

    def run(cmd, *args, **kwargs):
        if cmd[:3] == ["docker", "compose", "version"]:
            return subprocess.CompletedProcess(cmd, 0 if compose_ok else 1, "Docker Compose version test", "")
        if cmd[:2] == ["docker", "compose"]:
            return subprocess.CompletedProcess(cmd, 1, "", "no stack in unit tests")
        return real(cmd, *args, **kwargs)

    return run


def test_without_docker_compose_the_drill_stops_with_exit_2(monkeypatch, capsys):
    monkeypatch.setattr(drills.subprocess, "run", fake_run(compose_ok=False))
    assert drills.main(["worker-crash", "--api", "http://127.0.0.1:9"]) == 2
    assert "docker compose is required" in capsys.readouterr().err


def test_an_unreachable_stack_fails_within_the_deadline_with_diagnostics(monkeypatch, capsys):
    monkeypatch.setattr(drills.subprocess, "run", fake_run(compose_ok=True))
    started = time.monotonic()
    code = drills.main(["poison-message", "--api", "http://127.0.0.1:9", "--ready-seconds", "2",
                        "--request-timeout", "1"])
    assert code == 1 and time.monotonic() - started < 15
    err = capsys.readouterr().err
    assert "FAIL timed out after 2 s waiting for API readiness" in err and "diagnostics" in err
