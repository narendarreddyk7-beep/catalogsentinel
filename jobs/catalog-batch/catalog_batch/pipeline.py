"""The batch ingestion pipeline.

    manifest -> register job (idempotent) -> verify files -> parse + validate + normalize (Spark)
             -> in-export dedup -> curated Parquet + rejected JSONL -> publish events (partition markers)
             -> report PUBLISHED

Every stage can be re-run: the job key is derived from the manifest, outputs for a job are overwritten (never
appended), event ids are deterministic and already-published partitions are skipped. A crash at any point is
recovered by running the same command again.
"""
from __future__ import annotations

import functools
import json
import logging
import os
import shutil
import tempfile
import time
import zipfile
import zlib
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterable, Iterator, Optional

from pyspark.sql import DataFrame, SparkSession, Window
from pyspark.sql import functions as F
from pyspark.sql import types as T

from . import normalize as n
from . import storage
from .api_client import JobsApi
from .events import build_event
from .manifest import Manifest, ManifestError, parse_manifest, verify_files
from .publisher import PublishConfig, publish_partition
from .validation import UPSERTED, WITHDRAWN, ValidatedRecord, format_instant, from_csv_row, parse_instant, validate

log = logging.getLogger("catalog_batch")

MAX_RAW_CHARS = 10_000
CONFLICT_FIELD = "sourceVersion"
CONFLICT_MESSAGE = "same externalProductId and sourceVersion appear with different content in this export"

RECORD_SCHEMA = T.StructType([
    T.StructField("file", T.StringType(), False),
    T.StructField("record_index", T.LongType(), False),
    T.StructField("raw", T.StringType(), True),
    T.StructField("external_product_id", T.StringType(), True),
    T.StructField("source_version", T.LongType(), True),
    T.StructField("event_type", T.StringType(), False),
    T.StructField("occurred_at", T.StringType(), True),
    T.StructField("title", T.StringType(), True),
    T.StructField("brand", T.StringType(), True),
    T.StructField("model", T.StringType(), True),
    T.StructField("category", T.StringType(), True),
    T.StructField("gtin", T.StringType(), True),
    T.StructField("attributes", T.MapType(T.StringType(), T.StringType()), True),
    T.StructField("content_hash", T.StringType(), True),
    T.StructField("normalized_title", T.StringType(), True),
    T.StructField("normalized_brand", T.StringType(), True),
    T.StructField("normalized_model", T.StringType(), True),
    T.StructField("normalized_category", T.StringType(), True),
    T.StructField("category_leaf", T.StringType(), True),
    T.StructField("category_root", T.StringType(), True),
    T.StructField("fingerprint", T.StringType(), True),
    T.StructField("violations", T.ArrayType(T.StructType([
        T.StructField("field", T.StringType(), False),
        T.StructField("message", T.StringType(), False)])), False),
])


@dataclass
class JobOptions:
    manifest_uri: str
    output_uri: str
    rejected_uri: str
    state_uri: str
    queue_name: str = "catalog-listing-events"
    sqs_endpoint: Optional[str] = None
    region: str = "us-east-1"
    s3_mode: str = "staged"
    publish_partitions: int = 8
    work_dir: Optional[str] = None
    fail_from_partition: Optional[int] = None
    now: Optional[datetime] = None
    # Validate and write curated/rejected output only: no job registration, no events (export pre-checks,
    # benchmarks of the Spark stages).
    dry_run: bool = False


@dataclass
class JobResult:
    job_id: int
    job_key: str
    status: str
    skipped: bool = False
    attempt: int = 1
    rows_total: int = 0
    rows_valid: int = 0
    rows_rejected: int = 0
    events_published: int = 0
    events_sent_this_run: int = 0
    partitions_skipped: int = 0
    curated_uri: Optional[str] = None
    rejected_uri: Optional[str] = None
    rejection_reasons: dict[str, int] = field(default_factory=dict)
    stage_seconds: dict[str, float] = field(default_factory=dict)


def path_safe(job_key: str) -> str:
    """Job keys contain ':' which Hadoop paths do not accept; outputs use ``job=<key with '_'>``."""
    return "job=" + job_key.replace(":", "_")


# --------------------------------------------------------------------------------------- record processing

def process_records(rows: Iterable[tuple[str, int, str]], fmt: str, default_occurred_at: str,
                    now: str) -> Iterator[tuple]:
    """Executor-side: raw (file, index, text) -> validated, normalized tuple matching RECORD_SCHEMA."""
    default_dt = parse_instant(default_occurred_at)
    now_dt = parse_instant(now)
    for file_name, index, text in rows:
        raw = text if len(text) <= MAX_RAW_CHARS else text[:MAX_RAW_CHARS]
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError as e:
            yield _rejected_tuple(file_name, index, raw, "record", f"is not valid JSON: {e.msg}")
            continue
        if not isinstance(parsed, dict):
            yield _rejected_tuple(file_name, index, raw, "record", "must be a JSON object")
            continue
        record = from_csv_row(parsed) if fmt == "csv" else parsed
        v = validate(record, default_dt, now_dt)
        yield _tuple(file_name, index, raw, v)


def _rejected_tuple(file_name: str, index: int, raw: str, field_name: str, message: str) -> tuple:
    return (file_name, index, raw, None, None, UPSERTED, None, None, None, None, None, None, {}, None,
            None, None, None, None, None, None, None, [(field_name, message)])


def _tuple(file_name: str, index: int, raw: str, v: ValidatedRecord) -> tuple:
    occurred = format_instant(v.occurred_at) if v.occurred_at is not None else None
    norm = n.normalize_all(v.title, v.brand, v.model, v.category) if v.event_type == UPSERTED else {}
    content_hash = v.content_hash() if v.valid else None
    fingerprint = n.CanonicalForm().field("type", v.event_type).field("occurredAt", occurred) \
        .field("content", content_hash).sha256_hex() if v.valid else None
    return (file_name, index, raw, v.external_product_id, v.source_version, v.event_type, occurred,
            v.title, v.brand, v.model, v.category, v.gtin, dict(v.attributes), content_hash,
            norm.get("normalized_title"), norm.get("normalized_brand"), norm.get("normalized_model"),
            norm.get("normalized_category"), norm.get("category_leaf"), n.category_root(norm.get("normalized_category")),
            fingerprint, [(x.field, x.message) for x in v.violations])


def to_event_body(row: dict[str, Any], job_key: str, source_code: str, job_id: int) -> tuple[int, str]:
    """Executor-side: curated row -> (stable partition hash, event JSON)."""
    record = ValidatedRecord(row["external_product_id"], row["source_version"], row["event_type"],
                             parse_instant(row["occurred_at"]), row["title"], row["brand"], row["model"],
                             row["category"], row["gtin"], dict(row["attributes"] or {}))
    event = build_event(record, job_key=job_key, source_code=source_code, ingestion_job_id=job_id)
    return zlib.crc32(event["eventId"].encode("ascii")), json.dumps(event, separators=(",", ":"), ensure_ascii=False)


# --------------------------------------------------------------------------------------- spark stages

def read_records(spark: SparkSession, manifest: Manifest, local_files: dict[str, str]):
    """RDD of (file, record index, record text); CSV rows are re-encoded as JSON objects."""
    sc = spark.sparkContext
    rdds = []
    for f in manifest.files:
        name, local = f["path"], local_files[f["path"]]
        if manifest.fmt == "jsonl":
            lines = sc.textFile("file://" + os.path.abspath(local)).filter(lambda line: line.strip() != "")
            rdds.append(lines.zipWithIndex().map(functools.partial(_with_file, name)))
        else:
            df = (spark.read.option("header", True).option("multiLine", True).option("escape", '"')
                  .option("mode", "PERMISSIVE").csv("file://" + os.path.abspath(local)))
            declared, actual = int(f["records"]), df.count()
            if actual != declared:
                raise ManifestError(f"{name}: {actual} CSV records, manifest declares {declared}")
            rdds.append(df.rdd.map(lambda r: json.dumps(r.asDict(), ensure_ascii=False)).zipWithIndex()
                        .map(functools.partial(_with_file, name)))
    return sc.union(rdds) if len(rdds) > 1 else rdds[0]


def _with_file(name: str, pair: tuple[str, int]) -> tuple[str, int, str]:
    text, index = pair
    return name, index, text


def ship_package(spark: SparkSession, work_dir: str) -> None:
    """Makes ``catalog_batch`` importable in Python workers (local mode and clusters alike).

    Shipped once per SparkContext; the archive is byte-for-byte deterministic (sorted entries, fixed timestamps)
    because Spark refuses to replace a distributed file with different content.
    """
    sc = spark.sparkContext
    if getattr(sc, "_catalog_batch_shipped", False):
        return
    package_dir = os.path.dirname(os.path.abspath(__file__))
    archive = os.path.join(work_dir, "catalog_batch.zip")
    sources = sorted(os.path.relpath(os.path.join(root, name), package_dir)
                     for root, _, files in os.walk(package_dir) for name in files if name.endswith(".py"))
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in sources:
            info = zipfile.ZipInfo(os.path.join("catalog_batch", rel), date_time=(1980, 1, 1, 0, 0, 0))
            with open(os.path.join(package_dir, rel), "rb") as f:
                z.writestr(info, f.read())
    sc.addPyFile(archive)
    sc._catalog_batch_shipped = True


def run_job(spark: SparkSession, api: JobsApi, options: JobOptions) -> JobResult:
    manifest = parse_manifest(storage.read_text(options.manifest_uri))
    job_key = manifest.job_key
    safe = path_safe(job_key)
    curated_uri = storage.join(options.output_uri, safe)
    rejected_uri = storage.join(options.rejected_uri, safe)
    state_uri = storage.join(options.state_uri, safe)

    if options.dry_run:
        work_dir = options.work_dir or tempfile.mkdtemp(prefix="catalog-batch-")
        os.makedirs(work_dir, exist_ok=True)
        try:
            return _execute(spark, _DryRunApi(), options, manifest, 0, job_key, curated_uri, rejected_uri, state_uri,
                            work_dir)
        finally:
            if options.work_dir is None:
                shutil.rmtree(work_dir, ignore_errors=True)

    registration = api.register({
        "jobKey": job_key, "sourceCode": manifest.source_code, "manifest": manifest.raw,
        "inputUri": storage.parent(options.manifest_uri), "outputUri": curated_uri, "rejectedUri": rejected_uri,
        "rowsTotal": manifest.declared_records,
    })
    job = registration["job"]
    job_id = int(job["jobId"])
    if job["status"] == "PUBLISHED":
        log.info("job %s (%s) is already PUBLISHED; nothing to do", job_id, job_key)
        return JobResult(job_id, job_key, "PUBLISHED", skipped=True, attempt=job["attempt"],
                         rows_total=job.get("rowsTotal") or 0, rows_valid=job.get("rowsValid") or 0,
                         rows_rejected=job.get("rowsRejected") or 0, events_published=job.get("eventsPublished") or 0,
                         curated_uri=job.get("outputUri"), rejected_uri=job.get("rejectedUri"))
    running = api.update(job_id, {"status": "RUNNING"})["job"]
    work_dir = options.work_dir or tempfile.mkdtemp(prefix="catalog-batch-")
    os.makedirs(work_dir, exist_ok=True)
    try:
        result = _execute(spark, api, options, manifest, job_id, job_key, curated_uri, rejected_uri, state_uri,
                          work_dir)
        result.attempt = running["attempt"]
        return result
    except Exception as e:  # noqa: BLE001 - every failure is reported on the job, then re-raised
        log.exception("job %s failed", job_id)
        try:
            api.update(job_id, {"status": "FAILED", "errors": [f"{type(e).__name__}: {e}"[:2000]]})
        except Exception:  # noqa: BLE001
            log.exception("could not report failure of job %s", job_id)
        raise
    finally:
        if options.work_dir is None:
            shutil.rmtree(work_dir, ignore_errors=True)


def _execute(spark: SparkSession, api: JobsApi, options: JobOptions, manifest: Manifest, job_id: int, job_key: str,
             curated_uri: str, rejected_uri: str, state_uri: str, work_dir: str) -> JobResult:
    timer = _Timer()
    ship_package(spark, work_dir)
    input_dir = storage.parent(options.manifest_uri)
    local_files = {}
    for f in manifest.files:
        source = storage.join(input_dir, f["path"])
        local_files[f["path"]] = (storage.download(source, os.path.join(work_dir, "input", f["path"]))
                                  if storage.is_s3(source) else source)
    verify_files(manifest, local_files)
    timer.lap("stage_and_verify_inputs")

    now = format_instant(options.now or datetime.now(timezone.utc))
    raw = read_records(spark, manifest, local_files)
    records = spark.createDataFrame(
        raw.mapPartitions(functools.partial(process_records, fmt=manifest.fmt,
                                            default_occurred_at=format_instant(manifest.exported_at), now=now)),
        RECORD_SCHEMA).persist()
    try:
        return _stages(spark, api, options, manifest, job_id, job_key, curated_uri, rejected_uri, state_uri,
                       work_dir, records, timer)
    finally:
        records.unpersist()


def _stages(spark: SparkSession, api: JobsApi, options: JobOptions, manifest: Manifest, job_id: int, job_key: str,
            curated_uri: str, rejected_uri: str, state_uri: str, work_dir: str, records: DataFrame,
            timer: "_Timer") -> JobResult:
    valid = records.filter(F.size("violations") == 0)
    conflict_keys = (valid.groupBy("external_product_id", "source_version")
                     .agg(F.countDistinct("fingerprint").alias("variants"))
                     .filter(F.col("variants") > 1).select("external_product_id", "source_version"))
    conflicting = (valid.join(conflict_keys, ["external_product_id", "source_version"], "left_semi")
                   .withColumn("violations", F.array(F.struct(F.lit(CONFLICT_FIELD).alias("field"),
                                                              F.lit(CONFLICT_MESSAGE).alias("message")))))
    clean = valid.join(conflict_keys, ["external_product_id", "source_version"], "left_anti")
    rejected = records.filter(F.size("violations") > 0).unionByName(conflicting)

    # Latest version per listing: older versions in the same export are superseded (same final state, fewer
    # events). Ties are identical duplicates (same fingerprint), so any of them yields the same event.
    w = Window.partitionBy("external_product_id").orderBy(F.col("source_version").desc(), "file", "record_index")
    latest = clean.withColumn("_rn", F.row_number().over(w)).filter("_rn = 1").drop("_rn").persist()

    rows_total = records.count()
    rows_rejected = rejected.count()
    events_total = latest.count()
    reasons = {f"{r['field']}: {r['message']}": r["n"] for r in
               rejected.select(F.explode("violations").alias("v")).groupBy("v.field", "v.message")
               .agg(F.count(F.lit(1)).alias("n")).orderBy(F.desc("n")).limit(20).collect()}
    timer.lap("parse_validate_normalize_dedup")

    curated = (latest.select(
        F.lit(manifest.source_code).alias("source_code"), "external_product_id", "source_version",
        F.when(F.col("event_type") == WITHDRAWN, F.lit("WITHDRAWN")).otherwise(F.lit("ACTIVE")).alias("status"),
        "occurred_at", "title", "brand", "model", "category", "gtin", "attributes", "content_hash",
        "normalized_title", "normalized_brand", "normalized_model", "normalized_category", "category_leaf",
        F.coalesce("category_root", F.lit("uncategorized")).alias("category_root"),
        F.lit(n.VERSION).alias("normalizer_version"), F.lit(job_key).alias("job_key"),
        F.lit(job_id).cast("long").alias("ingestion_job_id"), F.lit(manifest.synthetic).alias("synthetic_data")))
    _write(curated.write.mode("overwrite").partitionBy("category_root").format("parquet"),
           curated_uri, os.path.join(work_dir, "curated"), options.s3_mode)
    _write(rejected.select("file", "record_index", "external_product_id", "violations", "raw")
           .coalesce(max(1, min(16, rows_rejected // 100_000 + 1))).write.mode("overwrite").format("json"),
           rejected_uri, os.path.join(work_dir, "rejected"), options.s3_mode)

    timer.lap("write_curated_and_rejected")
    counts = {"rowsTotal": rows_total, "rowsValid": rows_total - rows_rejected, "rowsRejected": rows_rejected}
    api.update(job_id, {"status": "RUNNING", **counts, "outputUri": curated_uri, "rejectedUri": rejected_uri})

    sent, skipped = (0, 0) if options.dry_run else _publish(latest, options, manifest, job_id, job_key, state_uri)
    latest.unpersist()
    timer.lap("publish_events")
    api.update(job_id, {"status": "PUBLISHED", **counts, "eventsPublished": events_total,
                        "outputUri": curated_uri, "rejectedUri": rejected_uri})
    return JobResult(job_id, job_key, "DRY_RUN" if options.dry_run else "PUBLISHED", rows_total=rows_total, rows_valid=rows_total - rows_rejected,
                     rows_rejected=rows_rejected, events_published=events_total, events_sent_this_run=sent,
                     partitions_skipped=skipped, curated_uri=curated_uri, rejected_uri=rejected_uri,
                     rejection_reasons=reasons, stage_seconds=timer.laps)


class _DryRunApi:
    """Accepts and ignores status reports in dry-run mode."""

    def register(self, body):  # pragma: no cover - never called
        raise AssertionError("dry runs do not register jobs")

    def update(self, job_id, body):
        return {"job": {"jobId": job_id, "status": body.get("status"), "attempt": 1}}

    def get(self, job_id):  # pragma: no cover
        raise AssertionError("dry runs have no job")


class _Timer:
    def __init__(self) -> None:
        self.laps: dict[str, float] = {}
        self._last = time.perf_counter()

    def lap(self, name: str) -> None:
        now = time.perf_counter()
        self.laps[name] = round(now - self._last, 3)
        self._last = now
        log.info("stage %s took %.2fs", name, self.laps[name])


def _write(writer, target_uri: str, local_dir: str, s3_mode: str) -> None:
    if storage.is_s3(target_uri) and s3_mode == "native":
        writer.save(target_uri.replace("s3://", "s3a://", 1) if target_uri.startswith("s3://") else target_uri)
        return
    if not storage.is_s3(target_uri):
        writer.save(os.path.abspath(target_uri))
        return
    writer.save("file://" + os.path.abspath(local_dir))
    storage.delete_prefix(target_uri)
    storage.upload_dir(local_dir, target_uri)


def _publish(latest: DataFrame, options: JobOptions, manifest: Manifest, job_id: int, job_key: str,
             state_uri: str) -> tuple[int, int]:
    partitions = max(1, options.publish_partitions)
    layout_uri = storage.join(state_uri, "published", "_layout.json")
    layout = json.dumps({"partitions": partitions, "jobKey": job_key})
    if storage.exists(layout_uri) and storage.read_text(layout_uri) != layout:
        # Partition count changed between attempts: markers no longer describe the same event sets.
        storage.delete_prefix(storage.join(state_uri, "published"))
    storage.write_text(layout_uri, layout)
    config = PublishConfig(queue_name=options.queue_name, state_uri=state_uri, region=options.region,
                           endpoint=options.sqs_endpoint, fail_from_partition=options.fail_from_partition)
    to_body = functools.partial(to_event_body, job_key=job_key, source_code=manifest.source_code, job_id=job_id)
    keyed = latest.rdd.map(lambda r: to_body(r.asDict()))
    # Partition = pure function of the event id, so every attempt groups the same events under the same marker.
    by_partition = keyed.partitionBy(partitions, lambda h: h % partitions).values()
    results = by_partition.mapPartitionsWithIndex(lambda i, it: publish_partition(i, it, config)).collect()
    return sum(r[1] for r in results), sum(1 for r in results if r[2])
