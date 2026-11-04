#!/usr/bin/env bash
# End-to-end smoke test against a running stack; the checks live in scripts/e2e/smoke.py (docs/testing.md#smoke-test).
#
#   docker compose up -d --build --wait && ./scripts/smoke.sh
#   ./scripts/smoke.sh --help                                      # options and environment variables
#   API=https://<host> API_KEY=<admin-key> SMOKE_BATCH=skip ./scripts/smoke.sh   # deployed stack, no compose
#
# Exit codes: 0 passed; 1 a check failed, timed out or a message reached a dead-letter queue (diagnostics printed);
# 2 usage or environment error. Every wait has a deadline (SMOKE_WAIT_SECONDS) and the run a hard limit
# (SMOKE_MAX_SECONDS).
set -euo pipefail
command -v python3 >/dev/null 2>&1 || { echo "smoke: python3 is required" >&2; exit 2; }
exec python3 "$(cd "$(dirname "$0")" && pwd)/e2e/smoke.py" "$@"
