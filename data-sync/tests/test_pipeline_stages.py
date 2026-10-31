from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import time

import adbc_driver_postgresql.dbapi as pg_dbapi
import pytest

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


def test_mirrored_storage_client_refetches_partial_cache_without_marker(
    tmp_path: Path,
) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    cached_path = mirror_dir / "scans" / "blob.jsonl.zst.encrypted"
    cached_path.parent.mkdir(parents=True)
    cached_path.write_bytes(b"partial")
    upstream = _StorageClient({blob_uri: b"remote"})
    storage_client = MirroredStorageClient(upstream, mirror_dir)

    blob_bytes = read_blob_bytes(storage_client, blob_uri)

    assert blob_bytes == b"remote"
    assert cached_path.read_bytes() == b"remote"
    assert upstream.read_uris == [blob_uri]


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
    assert read_blob_bytes(storage_client, blob_uri) == b"remote"
    assert upstream.read_uris == [blob_uri]


def test_mirrored_storage_client_refetches_corrupted_completed_cache(
    tmp_path: Path,
) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    upstream = _StorageClient({blob_uri: b"remote"})
    storage_client = MirroredStorageClient(upstream, mirror_dir)
    assert read_blob_bytes(storage_client, blob_uri) == b"remote"

    cached_path = mirror_dir / "scans" / "blob.jsonl.zst.encrypted"
    cached_path.write_bytes(b"bitrot")

    assert read_blob_bytes(storage_client, blob_uri) == b"remote"
    assert cached_path.read_bytes() == b"remote"
    assert upstream.read_uris == [blob_uri, blob_uri]


def test_mirrored_storage_client_serializes_concurrent_publication(
    tmp_path: Path,
) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"

    class SlowStorage(_StorageClient):
        def read_blob_bytes(self, blob_uri: str) -> bytes:
            time.sleep(0.05)
            return super().read_blob_bytes(blob_uri)

    upstream = SlowStorage({blob_uri: b"remote"})
    clients = [
        MirroredStorageClient(upstream, tmp_path / "mirror"),
        MirroredStorageClient(upstream, tmp_path / "mirror"),
    ]
    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(
            executor.map(
                lambda client: read_blob_bytes(client, blob_uri),
                clients,
            )
        )

    assert results == [b"remote", b"remote"]
    assert upstream.read_uris == [blob_uri]
    assert not list((tmp_path / "mirror").rglob("*.tmp"))


@pytest.mark.parametrize(
    "object_name",
    [
        "scans/../../escaped/device_0.0.4.jsonl.zst.encrypted",
        "/absolute/device_0.0.4.jsonl.zst.encrypted",
        "scans/./device_0.0.4.jsonl.zst.encrypted",
    ],
)
def test_mirrored_storage_client_rejects_paths_outside_root(
    tmp_path: Path,
    object_name: str,
) -> None:
    blob_uri = f"gs://test-bucket/{object_name}"
    storage_client = MirroredStorageClient(
        _StorageClient({blob_uri: b"remote"}), tmp_path / "mirror"
    )

    with pytest.raises(
        ValueError,
        match="refusing (unsafe mirror object path|mirror path outside root)",
    ):
        read_blob_bytes(storage_client, blob_uri)


def test_mirrored_storage_client_rejects_existing_symlink_traversal(
    tmp_path: Path,
) -> None:
    blob_uri = "gs://test-bucket/scans/device_0.0.4.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    outside = tmp_path / "outside"
    mirror_dir.mkdir()
    outside.mkdir()
    (mirror_dir / "scans").symlink_to(outside, target_is_directory=True)
    storage_client = MirroredStorageClient(
        _StorageClient({blob_uri: b"remote"}), mirror_dir
    )

    with pytest.raises(ValueError, match="outside root"):
        read_blob_bytes(storage_client, blob_uri)
    assert list(outside.iterdir()) == []


def test_mirrored_storage_client_rejects_sidecar_symlink_escape(
    tmp_path: Path,
) -> None:
    blob_uri = "gs://test-bucket/scans/blob.jsonl.zst.encrypted"
    mirror_dir = tmp_path / "mirror"
    outside = tmp_path / "outside"
    mirror_dir.mkdir()
    outside.mkdir()
    (mirror_dir / ".cluetooth-cache-metadata").symlink_to(
        outside, target_is_directory=True
    )
    storage_client = MirroredStorageClient(
        _StorageClient({blob_uri: b"remote"}), mirror_dir
    )

    with pytest.raises(ValueError, match="outside root"):
        read_blob_bytes(storage_client, blob_uri)
    assert list(outside.iterdir()) == []


def test_mirrored_storage_client_preserves_nested_payload_v2_path(
    tmp_path: Path,
) -> None:
    payload_id = "0195c920-7c00-7abc-8def-0123456789ab"
    blob_uri = f"gs://test-bucket/scans/v2/2025/03/24/{payload_id}.parquet.encrypted"
    mirror_dir = tmp_path / "mirror"
    upstream = _StorageClient({blob_uri: b"encrypted-parquet"})
    storage_client = MirroredStorageClient(upstream, mirror_dir)

    assert read_blob_bytes(storage_client, blob_uri) == b"encrypted-parquet"
    assert (
        mirror_dir / "scans/v2/2025/03/24" / f"{payload_id}.parquet.encrypted"
    ).read_bytes() == b"encrypted-parquet"
