#!/usr/bin/env bash
# Loads the SYNTHETIC demo catalog into the local stack; the steps live in scripts/e2e/seed_demo.py.
#
#   docker compose up -d --build --wait && ./scripts/seed-demo.sh [10k|100k]
#   ./scripts/seed-demo.sh --help
#
# Deterministic and idempotent: same exports, batch job keys, event ids and reconciliation Idempotency-Key on every
# run, so running it again changes nothing. Every step has a deadline inside a hard limit for the whole seed
# (SEED_MAX_SECONDS, default 1800 s for 10k and 7200 s for 100k). Exit codes: 0 done; 1 a step failed or timed out,
# or a message reached a dead-letter queue (diagnostics printed); 2 usage or environment error.
set -euo pipefail
command -v python3 >/dev/null 2>&1 || { echo "seed-demo: python3 is required" >&2; exit 2; }
exec python3 "$(cd "$(dirname "$0")" && pwd)/e2e/seed_demo.py" "$@"
