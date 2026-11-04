#!/usr/bin/env bash
# Failure drills against the local stack; the scenarios live in scripts/e2e/drills.py (docs/runbook.md#failure-drills).
#
#   docker compose up -d --build --wait && ./scripts/seed-demo.sh 10k
#   ./scripts/inject-failure.sh db-outage | worker-crash | poison-message | batch-crash
#
# Each drill provokes the failure and checks the documented outcome; it exits 0 only if every check holds.
# Exit codes: 0 passed; 1 a check failed or a wait timed out (diagnostics printed); 2 usage or environment error.
# Limits: DRILL_MAX_SECONDS (default 900) for the drill, DRILL_WAIT_SECONDS (300) for each recovery wait.
set -euo pipefail
command -v python3 >/dev/null 2>&1 || { echo "inject-failure: python3 is required" >&2; exit 2; }
exec python3 "$(cd "$(dirname "$0")" && pwd)/e2e/drills.py" "$@"
