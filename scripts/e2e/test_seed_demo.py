"""Unit tests for scripts/e2e/seed_demo.py that need no running stack: idempotency keys, limits and failure modes."""
from __future__ import annotations

import subprocess
import time

import pytest

import seed_demo


def test_the_reconciliation_key_depends_only_on_the_set_of_job_keys():
    a = seed_demo.reconciliation_key(["northwind-supply:abc", "acme-market:def", "contoso-wholesale:123"])
    b = seed_demo.reconciliation_key(["acme-market:def", "contoso-wholesale:123", "northwind-supply:abc"])
    assert a == b and a.startswith("seed-demo-") and len(a) <= 64
    assert seed_demo.reconciliation_key(["acme-market:other"]) != a


def test_the_default_limit_depends_on_the_size(monkeypatch):
    monkeypatch.delenv("SEED_MAX_SECONDS", raising=False)
    assert seed_demo.parse_args([]).max_seconds == 1800
    assert seed_demo.parse_args(["100k"]).max_seconds == 7200
    assert seed_demo.parse_args(["10k", "--max-seconds", "60"]).max_seconds == 60
    monkeypatch.setenv("SEED_MAX_SECONDS", "90")
    assert seed_demo.parse_args([]).max_seconds == 90


def test_invalid_arguments_exit_2(capsys):
    with pytest.raises(SystemExit) as e:
        seed_demo.parse_args(["5k"])
    assert e.value.code == 2
    assert seed_demo.main(["--max-seconds", "0"]) == 2


def fake_run(compose_ok: bool):
    real = subprocess.run

    def run(cmd, *args, **kwargs):
        if cmd[:3] == ["docker", "compose", "version"]:
            return subprocess.CompletedProcess(cmd, 0 if compose_ok else 1, "Docker Compose version test", "")
        if cmd[:2] == ["docker", "compose"]:
            return subprocess.CompletedProcess(cmd, 1, "", "no stack in unit tests")
        return real(cmd, *args, **kwargs)

    return run


def test_without_docker_compose_the_seed_stops_with_exit_2(monkeypatch, capsys):
    monkeypatch.setattr(seed_demo.subprocess, "run", fake_run(compose_ok=False))
    assert seed_demo.main(["--api", "http://127.0.0.1:9"]) == 2
    assert "docker compose is required" in capsys.readouterr().err


def test_an_api_that_never_becomes_ready_fails_within_the_deadline_with_diagnostics(monkeypatch, capsys):
    monkeypatch.setattr(seed_demo.subprocess, "run", fake_run(compose_ok=True))
    started = time.monotonic()
    code = seed_demo.main(["--api", "http://127.0.0.1:9", "--ready-seconds", "2", "--request-timeout", "1"])
    assert code == 1 and time.monotonic() - started < 15
    err = capsys.readouterr().err
    assert "seed-demo FAILED: timed out after 2 s waiting for API readiness" in err
    assert '"failedStep": "preflight"' in err and "run it again" in err
