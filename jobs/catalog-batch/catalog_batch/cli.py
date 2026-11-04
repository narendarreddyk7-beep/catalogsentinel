"""Command line entry point.

    python -m catalog_batch.cli run \\
        --manifest s3://catalogsentinel-batch/incoming/northwind-supply/2026-11-03/manifest.json \\
        --output s3://catalogsentinel-batch/curated/listings \\
        --rejected s3://catalogsentinel-batch/rejected \\
        --state s3://catalogsentinel-batch/_state \\
        --api-url http://localhost:8080

Exit codes: 0 success (or already published), 1 job failed (reported on the job), 2 invalid manifest or usage.
Configuration comes from flags or environment (``CATALOGSENTINEL_API_URL``, ``LISTING_EVENTS_QUEUE``,
``SQS_ENDPOINT``, ``S3_ENDPOINT``, ``AWS_REGION``); the API key only from ``CATALOGSENTINEL_API_KEY``.
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from dataclasses import asdict

from .api_client import ApiError, HttpJobsApi
from .manifest import ManifestError
from .pipeline import JobOptions, run_job


def build_spark(app_name: str = "catalogsentinel-batch", master: str | None = None):
    from pyspark.sql import SparkSession

    builder = (SparkSession.builder.appName(app_name)
               .config("spark.sql.session.timeZone", "UTC")
               .config("spark.ui.enabled", "false")
               .config("spark.sql.shuffle.partitions", os.environ.get("SPARK_SHUFFLE_PARTITIONS", "16")))
    # Without --master, spark-submit (or the local PySpark launcher, which defaults to local[*]) decides.
    if master:
        builder = builder.master(master)
    if (master or "local").startswith("local") and not os.environ.get("PYSPARK_PYTHON"):
        # Local mode: run Python workers with this interpreter so they see the same installed packages.
        os.environ["PYSPARK_PYTHON"] = sys.executable
    return builder.getOrCreate()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="catalog-batch")
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run", help="run (or resume) the batch job described by a manifest")
    run.add_argument("--manifest", required=True)
    run.add_argument("--output", required=True, help="base URI for curated Parquet")
    run.add_argument("--rejected", required=True, help="base URI for rejected records")
    run.add_argument("--state", required=True, help="base URI for restart state (publish markers)")
    run.add_argument("--api-url", default=os.environ.get("CATALOGSENTINEL_API_URL", "http://localhost:8080"))
    run.add_argument("--queue", default=os.environ.get("LISTING_EVENTS_QUEUE", "catalog-listing-events"))
    run.add_argument("--sqs-endpoint", default=os.environ.get("SQS_ENDPOINT") or None)
    run.add_argument("--region", default=os.environ.get("AWS_REGION", "us-east-1"))
    run.add_argument("--s3-mode", choices=["staged", "native"], default=os.environ.get("BATCH_S3_MODE", "staged"))
    run.add_argument("--publish-partitions", type=int, default=8)
    run.add_argument("--master", default=None, help="Spark master, default local[*]")
    run.add_argument("--dry-run", action="store_true",
                     help="validate and write curated/rejected output only; no job registration, no events")
    run.add_argument("--fail-from-partition", type=int, default=None, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    options = JobOptions(manifest_uri=args.manifest, output_uri=args.output, rejected_uri=args.rejected,
                         state_uri=args.state, queue_name=args.queue, sqs_endpoint=args.sqs_endpoint,
                         region=args.region, s3_mode=args.s3_mode, publish_partitions=args.publish_partitions,
                         fail_from_partition=args.fail_from_partition, dry_run=args.dry_run)
    spark = build_spark(master=args.master)
    spark.sparkContext.setLogLevel("WARN")
    try:
        result = run_job(spark, HttpJobsApi(args.api_url), options)
    except ManifestError as e:
        print(json.dumps({"status": "INVALID_MANIFEST", "error": str(e)}), file=sys.stderr)
        return 2
    except ApiError as e:
        print(json.dumps({"status": "API_ERROR", "httpStatus": e.status, "code": e.code, "error": str(e)}),
              file=sys.stderr)
        return 1
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"status": "FAILED", "error": f"{type(e).__name__}: {e}"}), file=sys.stderr)
        return 1
    finally:
        spark.stop()
    print(json.dumps(asdict(result), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
