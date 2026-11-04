"""Client for the CatalogSentinel ingestion-jobs API (``/api/v1/ingestion/jobs``).

Standard library only. Retries connection errors and 5xx responses with exponential backoff; 4xx responses are
returned to the caller as :class:`ApiError` with the problem-details ``code``. The API key, when security is
enabled, comes from ``CATALOGSENTINEL_API_KEY`` (never from the command line, where it would leak into process
listings).
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from typing import Any, Optional, Protocol


class ApiError(RuntimeError):
    def __init__(self, status: int, code: Optional[str], detail: str):
        super().__init__(f"HTTP {status} {code or ''}: {detail}".strip())
        self.status = status
        self.code = code


class JobsApi(Protocol):
    def register(self, body: dict[str, Any]) -> dict[str, Any]: ...

    def update(self, job_id: int, body: dict[str, Any]) -> dict[str, Any]: ...

    def get(self, job_id: int) -> dict[str, Any]: ...


class HttpJobsApi:
    def __init__(self, base_url: str, api_key: Optional[str] = None, timeout: float = 30.0, attempts: int = 4):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key if api_key is not None else os.environ.get("CATALOGSENTINEL_API_KEY")
        self.timeout = timeout
        self.attempts = attempts

    def register(self, body: dict[str, Any]) -> dict[str, Any]:
        return self._call("POST", "/api/v1/ingestion/jobs", body)

    def update(self, job_id: int, body: dict[str, Any]) -> dict[str, Any]:
        return self._call("PATCH", f"/api/v1/ingestion/jobs/{job_id}", body)

    def get(self, job_id: int) -> dict[str, Any]:
        return self._call("GET", f"/api/v1/ingestion/jobs/{job_id}", None)

    def _call(self, method: str, path: str, body: Optional[dict[str, Any]]) -> dict[str, Any]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if self.api_key:
            headers["X-Api-Key"] = self.api_key
        delay = 0.5
        for attempt in range(1, self.attempts + 1):
            request = urllib.request.Request(self.base_url + path, data=data, method=method, headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    return json.loads(response.read().decode("utf-8") or "{}")
            except urllib.error.HTTPError as e:
                payload = _problem(e)
                if e.code < 500 or attempt == self.attempts:
                    raise ApiError(e.code, payload.get("code"), payload.get("detail", str(e))) from None
            except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
                if attempt == self.attempts:
                    raise ApiError(0, "UNREACHABLE", str(e)) from None
            time.sleep(delay)
            delay *= 2
        raise AssertionError("unreachable")


def _problem(e: urllib.error.HTTPError) -> dict[str, Any]:
    try:
        return json.loads(e.read().decode("utf-8"))
    except Exception:  # noqa: BLE001 - best effort; the status code is what matters
        return {}
