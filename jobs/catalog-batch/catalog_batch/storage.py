"""Minimal object-storage abstraction: local paths and ``s3://`` URIs.

Spark reads and writes local paths here. For ``s3://`` locations two modes exist:

* ``staged`` (default; local development, tests, the docker compose stack with an S3 emulator): inputs are
  downloaded to a local staging directory with boto3 and outputs are uploaded after Spark has written them.
* ``native`` (EMR / Glue, which ship an S3 connector): Spark reads and writes ``s3://`` paths directly.

The S3 endpoint can be overridden with ``S3_ENDPOINT`` (or ``AWS_ENDPOINT_URL_S3``) for an emulator; in AWS the
default credential chain (task role) applies and no keys are configured anywhere.
"""
from __future__ import annotations

import os
import posixpath
import shutil
from dataclasses import dataclass
from typing import Iterator, Optional
from urllib.parse import urlparse


def s3_endpoint() -> Optional[str]:
    return os.environ.get("S3_ENDPOINT") or os.environ.get("AWS_ENDPOINT_URL_S3") or None


def is_s3(uri: str) -> bool:
    return uri.startswith("s3://") or uri.startswith("s3a://")


def join(base: str, *parts: str) -> str:
    if is_s3(base):
        return base.rstrip("/") + "/" + "/".join(p.strip("/") for p in parts)
    return os.path.join(base, *parts)


def parent(uri: str) -> str:
    if is_s3(uri):
        return uri.rsplit("/", 1)[0]
    return os.path.dirname(os.path.abspath(uri))


@dataclass(frozen=True)
class S3Location:
    bucket: str
    key: str

    @staticmethod
    def parse(uri: str) -> "S3Location":
        u = urlparse(uri)
        return S3Location(u.netloc, u.path.lstrip("/"))


def _client():
    import boto3  # imported lazily so pure-Python modules work without boto3 configuration

    return boto3.client("s3", endpoint_url=s3_endpoint(), region_name=os.environ.get("AWS_REGION", "us-east-1"))


def read_text(uri: str) -> str:
    if is_s3(uri):
        loc = S3Location.parse(uri)
        return _client().get_object(Bucket=loc.bucket, Key=loc.key)["Body"].read().decode("utf-8")
    with open(uri, "r", encoding="utf-8") as f:
        return f.read()


def write_text(uri: str, text: str) -> None:
    if is_s3(uri):
        loc = S3Location.parse(uri)
        _client().put_object(Bucket=loc.bucket, Key=loc.key, Body=text.encode("utf-8"))
        return
    os.makedirs(os.path.dirname(os.path.abspath(uri)), exist_ok=True)
    tmp = uri + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, uri)  # atomic on POSIX: a marker is either absent or complete


def exists(uri: str) -> bool:
    if is_s3(uri):
        loc = S3Location.parse(uri)
        resp = _client().list_objects_v2(Bucket=loc.bucket, Prefix=loc.key, MaxKeys=1)
        return any(o["Key"] == loc.key for o in resp.get("Contents", []))
    return os.path.exists(uri)


def list_keys(prefix_uri: str) -> Iterator[str]:
    """Object URIs under a prefix (recursive)."""
    if is_s3(prefix_uri):
        loc = S3Location.parse(prefix_uri)
        paginator = _client().get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=loc.bucket, Prefix=loc.key.rstrip("/") + "/"):
            for o in page.get("Contents", []):
                yield f"s3://{loc.bucket}/{o['Key']}"
        return
    for root, _, files in os.walk(prefix_uri):
        for name in files:
            yield os.path.join(root, name)


def download(uri: str, local_path: str) -> str:
    os.makedirs(os.path.dirname(os.path.abspath(local_path)), exist_ok=True)
    if is_s3(uri):
        loc = S3Location.parse(uri)
        _client().download_file(loc.bucket, loc.key, local_path)
    else:
        shutil.copyfile(uri, local_path)
    return local_path


def delete_prefix(prefix_uri: str) -> None:
    """Removes previous output of the same job before it is rewritten (restarts overwrite, never append)."""
    if is_s3(prefix_uri):
        client = _client()
        keys = [S3Location.parse(u).key for u in list_keys(prefix_uri)]
        bucket = S3Location.parse(prefix_uri).bucket
        for i in range(0, len(keys), 1000):
            client.delete_objects(Bucket=bucket, Delete={"Objects": [{"Key": k} for k in keys[i:i + 1000]]})
        return
    shutil.rmtree(prefix_uri, ignore_errors=True)


def upload_dir(local_dir: str, prefix_uri: str) -> int:
    """Uploads a directory tree (Spark output) to an S3 prefix or copies it locally; returns files copied."""
    count = 0
    if is_s3(prefix_uri):
        client = _client()
        loc = S3Location.parse(prefix_uri)
        for root, _, files in os.walk(local_dir):
            for name in files:
                if name.endswith(".crc"):
                    continue
                path = os.path.join(root, name)
                rel = os.path.relpath(path, local_dir).replace(os.sep, "/")
                client.upload_file(path, loc.bucket, posixpath.join(loc.key.rstrip("/"), rel))
                count += 1
        return count
    if os.path.abspath(local_dir) != os.path.abspath(prefix_uri):
        shutil.rmtree(prefix_uri, ignore_errors=True)
        shutil.copytree(local_dir, prefix_uri, ignore=shutil.ignore_patterns("*.crc"))
    for _, _, files in os.walk(prefix_uri):
        count += len(files)
    return count
