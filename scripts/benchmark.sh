#!/usr/bin/env bash
# Benchmark run with an environment profile (docs/benchmarking.md). Writes build/benchmarks/<UTC time>/report.md and
# the raw output of every section next to it. A section whose prerequisites are missing is reported as SKIPPED with
# the reason, never silently; a section that fails is reported as FAILED and the run continues.
#
#   ./scripts/benchmark.sh                                  # every section this machine can run
#   BENCH_SECTIONS="env generate quality" ./scripts/benchmark.sh
#
# Sections: env generate quality spark ingest match latency plans http
#   quality            needs compiled catalog-domain classes: built with Maven, or EVAL_CLASSPATH
#   spark              needs Python with PySpark and the batch package: BATCH_PYTHON (default python3)
#   ingest/match/latency  need the test classpath of libs/catalog-core (Maven, or BENCH_CLASSPATH) and a scratch
#                      PostgreSQL database THAT THE BENCHMARK EMPTIES: BENCH_JDBC_URL (its name must contain "bench")
#                      and BENCH_PSQL (a psql command for it, used for the schema, settings and query plans)
#   plans              runs scripts/bench/repair-plans.sql after "latency" loaded and reconciled the database
#   http               needs the stack (API at $API, API_KEY when security is on): HTTP latency percentiles,
#                      event-to-catalog latency and queue depth under load
# Sizes: BENCH_RUNS (3) ingestion runs; BENCH_EVENTS (10000) events per source; BENCH_THREADS (8);
# BENCH_MATCH_LISTINGS (3000); BENCH_LATENCY_EVENTS (2500) events per source for the latency section.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2
ROOT=$PWD
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
OUT=${BENCH_OUT:-build/benchmarks/$STAMP}
mkdir -p "$OUT"
REPORT=$OUT/report.md
SECTIONS=${BENCH_SECTIONS:-env generate quality spark ingest match latency plans http}
RUNS=${BENCH_RUNS:-3}
EVENTS=${BENCH_EVENTS:-10000}
THREADS=${BENCH_THREADS:-8}
MATCH=${BENCH_MATCH_LISTINGS:-3000}
LAT_EVENTS=${BENCH_LATENCY_EVENTS:-2500}
API=${API:-http://localhost:8080}
BATCH_PYTHON=${BATCH_PYTHON:-python3}
DATA=build/bench-100k

say() { printf '%s\n' "$*" | tee -a "$REPORT"; }
skip() { say ""; say "_SKIPPED: $1_"; }
fail() { say ""; say "**FAILED: $1** (raw output: \`$2\`)"; }
want() { [[ " $SECTIONS " == *" $1 "* ]]; }
stack_up() { # readiness probe, or an authenticated read where a load balancer blocks /actuator (as on AWS)
  curl -fsS -m 3 -o /dev/null "$API/actuator/health/readiness" 2>/dev/null \
    || curl -fsS -m 5 -o /dev/null -H "X-Api-Key: ${API_KEY:-}" "$API/api/v1/dashboard/summary" 2>/dev/null
}

classpath_from_maven() { # module -> prints a classpath with test classes, or nothing
  command -v mvn >/dev/null 2>&1 || return 1
  mvn -B -q -ntp -pl "$1" -am test-compile >"$OUT/maven-build.log" 2>&1 || return 1
  mvn -B -q -ntp -pl "$1" dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$OUT/cp.txt" \
    >>"$OUT/maven-build.log" 2>&1 || return 1
  printf '%s' "$1/target/test-classes:$1/target/classes:$(cat "$OUT/cp.txt")"
}

bench_db_ok() {
  [[ -n "${BENCH_JDBC_URL:-}" && -n "${BENCH_PSQL:-}" ]] || { echo "BENCH_JDBC_URL and BENCH_PSQL are not set"; return 1; }
  [[ "${BENCH_JDBC_URL##*/}" == *bench* ]] || { echo "refusing: the database in BENCH_JDBC_URL must contain 'bench' (it is emptied)"; return 1; }
  $BENCH_PSQL -Atqc 'SELECT 1' >/dev/null 2>&1 || { echo "cannot connect with BENCH_PSQL"; return 1; }
  if [[ "$($BENCH_PSQL -Atqc "SELECT to_regclass('reconciliation_jobs') IS NOT NULL")" != t ]]; then
    for f in db/migrations/V*.sql; do $BENCH_PSQL -q -v ON_ERROR_STOP=1 --single-transaction -f "$f" >/dev/null || return 1; done
  fi
}

ingestion_benchmark() { # raw-log args... -> runs IngestionBenchmark; prints its table rows
  local log=$1; shift
  CATALOGSENTINEL_TEST_JDBC_URL=$BENCH_JDBC_URL java -cp "$BENCH_CLASSPATH" \
    io.catalogsentinel.core.benchmark.IngestionBenchmark "$@" >"$log" 2>&1 || return 1
  grep -E '^\| (ingest|check|redelivery|match|event-to-link|reconcile) ' "$log"
}

: >"$REPORT"
say "# Benchmark run $STAMP"
say ""
say "Commit \`$(git rev-parse --short HEAD 2>/dev/null || echo unknown)\`$(git diff --quiet 2>/dev/null || echo ' with uncommitted changes'). Sections: $SECTIONS."

if want env; then
  say ""; say "## Environment profile"; say ""
  {
    echo "| | |"; echo "|---|---|"
    echo "| CPU | $(nproc) vCPU, $( (lscpu 2>/dev/null | sed -n 's/^Model name: *//p'; sysctl -n machdep.cpu.brand_string 2>/dev/null) | head -1) |"
    echo "| Memory | $( (free -m 2>/dev/null | awk '/Mem/{print $2 " MiB"}'; sysctl -n hw.memsize 2>/dev/null | awk '{print int($1/1048576) " MiB"}') | head -1) |"
    echo "| Load average at start | $(uptime | sed 's/.*load average[s]*: //') |"
    echo "| OS | $(uname -sr) |"
    echo "| Java | $(java -version 2>&1 | grep -v '^Picked up' | head -1) |"
    echo "| Python | $(python3 -V 2>&1); batch: $($BATCH_PYTHON -c 'import pyspark; print("PySpark", pyspark.__version__)' 2>/dev/null || echo 'PySpark not available') |"
    if [[ -n "${BENCH_PSQL:-}" ]] && $BENCH_PSQL -Atqc 'SELECT 1' >/dev/null 2>&1; then
      echo "| PostgreSQL | $($BENCH_PSQL -Atqc 'SELECT version()' | cut -d, -f1) |"
      echo "| PostgreSQL settings | $($BENCH_PSQL -Atqc "SELECT string_agg(name || '=' || current_setting(name), ', ' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers','work_mem','synchronous_commit','random_page_cost','max_connections','effective_cache_size','fsync')") |"
    else
      echo "| PostgreSQL | not configured (BENCH_PSQL) |"
    fi
    if stack_up; then stack=up; else stack="not running"; fi
    echo "| Stack at $API | $stack |"
  } | tee -a "$REPORT"
fi

if want generate; then
  say ""; say "## Synthetic data generation"; say ""; say "| size | wall time | peak RSS of the generator |"; say "|---|---|---|"
  for size in 10k 100k; do
    python3 - "$size" <<'PY' | tee -a "$REPORT"
import resource, subprocess, sys, time
size = sys.argv[1]
t0 = time.perf_counter()
subprocess.run([sys.executable, "scripts/generate-synthetic-data.py", "--size", size, "--out", f"build/bench-{size}",
                "--seed", "7"], check=True, stdout=subprocess.DEVNULL)
rss = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
rss_mib = rss / 1024 if sys.platform != "darwin" else rss / 1048576
print(f"| {size} | {time.perf_counter() - t0:.2f} s | {rss_mib:.0f} MiB |")
PY
  done
fi

if want quality; then
  say ""; say "## Matching quality and matcher speed (EvaluationCli, in memory)"
  EVAL_CP=${EVAL_CLASSPATH:-}
  if [[ -z "$EVAL_CP" ]] && classpath_from_maven libs/catalog-domain >/dev/null 2>&1; then EVAL_CP=libs/catalog-domain/target/classes; fi
  if [[ -z "$EVAL_CP" ]]; then
    skip "no catalog-domain classes (Maven build failed or unavailable, see $OUT/maven-build.log; or set EVAL_CLASSPATH)"
  elif [[ ! -f build/bench-10k/truth/labeled-sample.tsv ]]; then
    skip "build/bench-10k is missing (run the generate section)"
  else
    for set in tests/golden-matching/golden-listings.tsv tests/golden-matching/golden-holdout.tsv build/bench-10k/truth/labeled-sample.tsv; do
      name=$(basename "$set" .tsv)
      if java -cp "$EVAL_CP" io.catalogsentinel.domain.evaluation.EvaluationCli "$set" >"$OUT/quality-$name.md" 2>&1; then
        say ""; say "### $set"; say ""
        grep -E '^- listings|^\| candidate pairs|^\|---|^\| [0-9]+ \||^\| matcher|rules-v4' "$OUT/quality-$name.md" | tee -a "$REPORT"
      else
        fail "EvaluationCli $set" "$OUT/quality-$name.md"
      fi
    done
  fi
fi

if want spark; then
  say ""; say "## Batch pipeline, dry run (Spark stages only, local[*])"
  if ! $BATCH_PYTHON -c 'import pyspark' >/dev/null 2>&1; then
    skip "$BATCH_PYTHON has no PySpark (set BATCH_PYTHON to the batch job's environment)"
  elif [[ ! -f $DATA/northwind-supply/manifest.json ]]; then
    skip "$DATA is missing (run the generate section)"
  else
    if (cd jobs/catalog-batch && JAVA_TOOL_OPTIONS='' $BATCH_PYTHON -m catalog_batch.cli run --dry-run --master 'local[*]' \
          --manifest "$ROOT/$DATA/northwind-supply/manifest.json" --output "$ROOT/$OUT/spark/curated" \
          --rejected "$ROOT/$OUT/spark/rejected" --state "$ROOT/$OUT/spark/state") >"$OUT/spark.json" 2>"$OUT/spark.log"; then
      python3 - "$OUT/spark.json" <<'PY' | tee -a "$REPORT"
import json, sys
r = json.load(open(sys.argv[1]))
print("\n| records | rejected | stage seconds |\n|---|---|---|")
print(f"| {r['rows_total']} | {r['rows_rejected']} | {json.dumps(r.get('stage_seconds'))} |")
PY
    else
      fail "batch dry run" "$OUT/spark.log"
    fi
  fi
fi

DB_READY=""
if want ingest || want match || want latency || want plans; then
  if [[ -z "${BENCH_CLASSPATH:-}" ]]; then BENCH_CLASSPATH=$(classpath_from_maven libs/catalog-core 2>/dev/null || true); fi
  if [[ -z "${BENCH_CLASSPATH:-}" ]]; then
    DB_READY="no catalog-core test classpath (Maven build failed or unavailable, see $OUT/maven-build.log; or set BENCH_CLASSPATH)"
  elif [[ ! -f $DATA/acme-market/listings-00001.jsonl ]]; then
    DB_READY="$DATA is missing (run the generate section)"
  elif ! reason=$(bench_db_ok); then
    DB_READY=$reason
  fi
fi
INPUTS="$DATA/northwind-supply/listings-00001.jsonl:northwind-supply,$DATA/acme-market/listings-00001.jsonl:acme-market"

if want ingest; then
  say ""; say "## Ingestion engine against PostgreSQL ($((EVENTS * 2)) events, two sources, $THREADS threads, $RUNS runs)"
  if [[ -n "$DB_READY" ]]; then skip "$DB_READY"; else
    say ""; say "| run | events | threads | wall time | throughput | p50 | p95 | p99 |"; say "|---|---|---|---|---|---|---|---|"
    for run in $(seq 1 "$RUNS"); do
      if rows=$(ingestion_benchmark "$OUT/ingest-$run.log" "$INPUTS" "$EVENTS" "$THREADS"); then
        printf '%s\n' "$rows" | awk -v r="$run" -F'|' '$2 ~ /ingest/ {
            gsub(/ events | threads |p50 |p95 |p99 /, " ", $0); n = split($0, c, "|");
            print "| " r " |" c[3] "|" c[4] "|" c[5] "|" c[6] "|" c[7] "|" c[8] "|" c[9] "|"}' | tr -s ' ' | tee -a "$REPORT"
        printf '%s\n' "$rows" | grep -E '^\| (check|redelivery) ' >>"$OUT/ingest-checks.txt"
      else
        fail "ingestion run $run" "$OUT/ingest-$run.log"
      fi
    done
    [[ -f $OUT/ingest-checks.txt ]] && { say ""; say "Correctness after each run:"; say ""; sed 's/^/    /' "$OUT/ingest-checks.txt" | tee -a "$REPORT"; }
  fi
fi

if want match; then
  say ""; say "## DB-backed matching ($MATCH listings matched against $((EVENTS * 2)))"
  if [[ -n "$DB_READY" ]]; then skip "$DB_READY"; else
    if rows=$(ingestion_benchmark "$OUT/match.log" "$INPUTS" "$EVENTS" "$THREADS" "$MATCH"); then
      say ""; printf '%s\n' "$rows" | grep -E '^\| match ' | tee -a "$REPORT"
    else
      fail "matching run" "$OUT/match.log"
    fi
  fi
fi

if want latency; then
  say ""; say "## Event-to-link latency in process, and one reconciliation ($((LAT_EVENTS * 2)) listings, all matched)"
  if [[ -n "$DB_READY" ]]; then skip "$DB_READY"; else
    if rows=$(ingestion_benchmark "$OUT/latency.log" "$INPUTS" "$LAT_EVENTS" "$THREADS" $((LAT_EVENTS * 2)) 250 acme-market); then
      say ""; printf '%s\n' "$rows" | grep -E '^\| (match|event-to-link|reconcile) ' | tee -a "$REPORT"
      say ""; say "Event-to-link is one event applied and matched on the calling thread, as the worker's two consumers do;"
      say "it excludes SQS, the outbox relay and consumer polling, which only the http section measures."
    else
      fail "latency run" "$OUT/latency.log"
    fi
  fi
fi

if want plans; then
  say ""; say "## Query plans for the statements added by the repair pass"
  if [[ -n "$DB_READY" ]]; then skip "$DB_READY"
  elif [[ "$($BENCH_PSQL -Atqc 'SELECT count(*) > 0 FROM selection_gaps' 2>/dev/null)" != t ]]; then
    skip "the bench database has no gaps (run the latency section first)"
  elif $BENCH_PSQL -f scripts/bench/repair-plans.sql >"$OUT/plans.txt" 2>&1; then
    say ""; say "Full plans: \`$OUT/plans.txt\`. Execution times:"; say ""
    awk '/^== /{title=$0} /Execution Time/{print "- " substr(title, 4) ": " $3 " " $4}' "$OUT/plans.txt" | tee -a "$REPORT"
  else
    fail "query plans" "$OUT/plans.txt"
  fi
fi

if want http; then
  say ""; say "## Through the stack: HTTP latency and event-to-catalog latency"
  if ! stack_up; then
    skip "the stack is not running at $API (docker compose up -d --build --wait)"
  elif python3 scripts/e2e/http_bench.py --api "$API" --api-key "${API_KEY:-}" --out "$OUT/http.json" >"$OUT/http.log" 2>&1; then
    say ""; grep '^|' "$OUT/http.log" | tee -a "$REPORT"
  else
    fail "http benchmark" "$OUT/http.log"
  fi
fi

say ""; say "Written to $REPORT"
