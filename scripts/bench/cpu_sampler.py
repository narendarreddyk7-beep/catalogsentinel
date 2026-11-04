#!/usr/bin/env python3
"""Runs a command and reports its CPU and peak memory, plus the CPU used by PostgreSQL meanwhile (Linux only).

    python3 scripts/bench/cpu_sampler.py java -cp ... io.catalogsentinel.core.benchmark.IngestionBenchmark ...

Prints one JSON line on stderr: wall time, PostgreSQL CPU seconds and average cores (sum of utime + stime of every
process named postgres*, sampled every 200 ms), host CPU busy share (/proc/stat), and the command's own CPU and
peak RSS (from wait4 rusage). Used for the CPU and memory columns of docs/benchmarking.md.
"""
import json
import os
import subprocess
import sys
import time

TICK = os.sysconf("SC_CLK_TCK")


def postgres_cpu_ticks():
    out = {}
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/stat") as f:
                head, tail = f.read().rsplit(")", 1)
            if head.split("(", 1)[1].startswith("postgres"):
                fields = tail.split()
                out[int(pid)] = int(fields[11]) + int(fields[12])
        except (FileNotFoundError, ProcessLookupError, IndexError):
            pass
    return out


def host_busy():
    with open("/proc/stat") as f:
        values = [int(x) for x in f.readline().split()[1:]]
    idle = values[3] + values[4]
    return sum(values) - idle, sum(values)


def main():
    baseline = postgres_cpu_ticks()
    last = dict(baseline)
    busy0, total0 = host_busy()
    started = time.time()
    child = subprocess.Popen(sys.argv[1:])
    while True:
        pid, status, usage = os.wait4(child.pid, os.WNOHANG)
        if pid:
            break
        for p, ticks in postgres_cpu_ticks().items():
            last[p] = max(ticks, last.get(p, 0))
        time.sleep(0.2)
    wall = time.time() - started
    busy1, total1 = host_busy()
    pg_cpu = sum(last[p] - baseline.get(p, 0) for p in last) / TICK
    child_cpu = usage.ru_utime + usage.ru_stime
    print(json.dumps({
        "wall_s": round(wall, 1),
        "postgres_cpu_s": round(pg_cpu, 1), "postgres_avg_cores": round(pg_cpu / wall, 2),
        "host_busy_pct": round(100 * (busy1 - busy0) / max(1, total1 - total0), 1),
        "command_cpu_s": round(child_cpu, 1), "command_avg_cores": round(child_cpu / wall, 2),
        "command_max_rss_mib": round(usage.ru_maxrss / 1024),
        "exit": os.waitstatus_to_exitcode(status)}), file=sys.stderr)
    sys.exit(os.waitstatus_to_exitcode(status))


if __name__ == "__main__":
    main()
