from __future__ import annotations

import asyncio
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import adbc_driver_postgresql.dbapi as pg_dbapi
import polars as pl
import pytest

from cluetooth_sync.pipeline import orchestrate
from cluetooth_sync.pipeline import (
    MirroredStorageClient,
    discover_pending_blobs,
    read_blob_bytes,
)


class _StorageClient:
    def __init__(self, blobs: dict[str, bytes]) -> None:
        self._blobs = blobs
        self.read_uris: list[str] = []

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        uri_prefix = f"gs://{bucket_name}/{prefix}"
        return [uri for uri in self._blobs if uri.startswith(uri_prefix)]

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        self.read_uris.append(blob_uri)
        return self._blobs[blob_uri]


def test_discover_pending_blobs_skips_succeeded(database_url: str) -> None:
    prefix = "stage-test/discover/"
    succeeded_uri = f"gs://test-bucket/{prefix}succeeded.jsonl.zst.encrypted"
    failed_uri = f"gs://test-bucket/{prefix}failed.jsonl.zst.encrypted"
    pending_uri = f"gs://test-bucket/{prefix}pending.jsonl.zst.encrypted"

    connection = pg_dbapi.connect(database_url, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute(
            """
            insert into blobs (uri, success)
            values ($1, true), ($2, false)
            on conflict (uri) do update
            set success = excluded.success
            """,
            (succeeded_uri, failed_uri),
        )
    finally:
        cursor.close()
        connection.close()

    storage_client = _StorageClient(
        {
            succeeded_uri: b"",
            failed_uri: b"",
            pending_uri: b"",
            "gs://test-bucket/other-prefix/ignored.jsonl.zst.encrypted": b"",
        }
    )

    pending = list(
        discover_pending_blobs(
            storage_client,
            database_url,
            "test-bucket",
            prefix,
        )
    )

    assert pending == [failed_uri, pending_uri]


def test_read_blob_bytes() -> None:
    blob_uri = "gs://test-bucket/path/blob.jsonl.zst.encrypted"
    storage_client = _StorageClient({blob_uri: b"ciphertext"})

    blob_bytes = read_blob_bytes(
        storage_client,
        blob_uri,
    )

    assert blob_bytes == b"ciphertext"
    assert storage_client.read_uris == [blob_uri]


def test_mirrored_storage_client_uses_cached_blob(tmp_path: Path) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    cached_path = mirror_dir / "scans" / "blob.jsonl.zst.encrypted"
    cached_path.parent.mkdir(parents=True)
    cached_path.write_bytes(b"cached")
    upstream = _StorageClient({blob_uri: b"remote"})
    storage_client = MirroredStorageClient(upstream, mirror_dir)

    blob_bytes = read_blob_bytes(storage_client, blob_uri)

    assert blob_bytes == b"cached"
    assert upstream.read_uris == []


def test_mirrored_storage_client_populates_missing_blob(tmp_path: Path) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    upstream = _StorageClient({blob_uri: b"remote"})
    storage_client = MirroredStorageClient(upstream, mirror_dir)

    blob_bytes = read_blob_bytes(storage_client, blob_uri)

    mirror_path = mirror_dir / "scans" / "blob.jsonl.zst.encrypted"
    assert blob_bytes == b"remote"
    assert mirror_path.read_bytes() == b"remote"
    assert upstream.read_uris == [blob_uri]


def test_run_pipeline_uses_single_db_writer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    blob_uris = [
        "gs://test-bucket/scans/a.jsonl.zst.encrypted",
        "gs://test-bucket/scans/b.jsonl.zst.encrypted",
        "gs://test-bucket/scans/c.jsonl.zst.encrypted",
        "gs://test-bucket/scans/d.jsonl.zst.encrypted",
    ]
    storage_client = _StorageClient({uri: b"" for uri in blob_uris})

    def discover_blobs(
        storage_client: orchestrate.StorageClient,
        database_url: str,
        bucket_name: str,
        prefix: str,
    ) -> list[str]:
        return blob_uris

    def read_blob(
        storage_client: orchestrate.StorageClient,
        blob_uri: str,
    ) -> orchestrate.BlobBytes:
        return orchestrate.BlobBytes(uri=blob_uri, encrypted_bytes=b"encrypted")

    def prepare_blob(
        private_key: bytes,
        blob: orchestrate.BlobBytes,
    ) -> orchestrate.PreparedBlob:
        time.sleep(0.01)
        return orchestrate.PreparedBlob(
            uri=blob.uri,
            scans=pl.DataFrame({"blob": [blob.uri]}),
        )

    active_writes = 0
    max_active_writes = 0
    written_uris: list[str] = []
    write_lock = threading.Lock()

    def write_blob(
        database_url: str,
        blob: orchestrate.PreparedBlob,
    ) -> None:
        nonlocal active_writes, max_active_writes

        with write_lock:
            active_writes += 1
            max_active_writes = max(max_active_writes, active_writes)

        time.sleep(0.02)
        written_uris.append(blob.uri)

        with write_lock:
            active_writes -= 1

    monkeypatch.setattr(orchestrate, "discover_pending_blobs", discover_blobs)
    monkeypatch.setattr(orchestrate, "_read_blob", read_blob)
    monkeypatch.setattr(orchestrate, "_prepare_blob", prepare_blob)
    monkeypatch.setattr(orchestrate, "_write_blob", write_blob)

    with (
        ThreadPoolExecutor(max_workers=4) as download_executor,
        ThreadPoolExecutor(max_workers=4) as ingest_executor,
    ):
        asyncio.run(
            orchestrate.run_pipeline(
                storage_client=storage_client,
                database_url="postgresql://example",
                bucket_name="test-bucket",
                prefix="scans/",
                private_key=b"private-key",
                download_executor=download_executor,
                ingest_executor=ingest_executor,
                download_workers=4,
                ingest_workers=4,
                queue_size=2,
            )
        )

    assert max_active_writes == 1
    assert sorted(written_uris) == sorted(blob_uris)
