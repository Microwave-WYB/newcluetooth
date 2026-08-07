from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor

import compression.zstd as zstd
import polars as pl
import pytest

from cluetooth_sync.pipeline.orchestrate import run_pipeline

PAYLOAD_ID = "0195c920-7c00-7abc-8def-0123456789ab"
V2_URI = f"gs://bucket/scans/v2/2025/03/24/{PAYLOAD_ID}.parquet.encrypted"
LEGACY_URIS = [
    f"gs://bucket/scans/2025-01-0{index}T00-00-00Z_device_0.0.4.jsonl.zst.encrypted"
    for index in range(1, 4)
]
UNSUPPORTED_URI = "gs://bucket/scans/schema=vNext/device_0.0.4.jsonl.zst.encrypted"
READ_FAILURE_URI = (
    "gs://bucket/scans/2025-01-04T00-00-00Z_device_0.0.4.jsonl.zst.encrypted"
)


class _Storage:
    def __init__(self) -> None:
        self.reads: list[str] = []

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        raise AssertionError("discovery is monkeypatched")

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        self.reads.append(blob_uri)
        if blob_uri == READ_FAILURE_URI:
            raise OSError("simulated read failure")
        if blob_uri == V2_URI:
            return b"v1"
        return zstd.compress(blob_uri.encode())


def test_bounded_multiworker_pipeline_drains_mixed_peer_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    discovered = [
        LEGACY_URIS[0],
        V2_URI,
        UNSUPPORTED_URI,
        LEGACY_URIS[1],
        READ_FAILURE_URI,
        LEGACY_URIS[2],
    ]
    failures: list[tuple[str, str]] = []
    inserted: list[str] = []
    storage = _Storage()

    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.discover_pending_blobs",
        lambda **kwargs: iter(discovered),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.decrypt_blob_bytes",
        lambda encrypted, private_key: encrypted,
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.prepare_scan_jsonl_bytes",
        lambda data, uri: pl.DataFrame({"blob": [uri]}),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.prepare_payload_v2_parquet_bytes",
        lambda data, route: pl.DataFrame({"blob": [route.uri]}),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.insert_prepared_scans",
        lambda database_url, scans, mark_failure: (
            inserted.append(scans.item(0, "blob")) or scans.height
        ),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.mark_blob_failed",
        lambda database_url, uri, exc: failures.append((uri, str(exc))),
    )

    async def run() -> int:
        with (
            ThreadPoolExecutor(max_workers=3) as downloads,
            ThreadPoolExecutor(max_workers=2) as ingests,
        ):
            return await asyncio.wait_for(
                run_pipeline(
                    storage_client=storage,
                    database_url="db",
                    bucket_name="bucket",
                    prefix="scans/",
                    private_key=b"key",
                    download_executor=downloads,
                    ingest_executor=ingests,
                    download_workers=3,
                    ingest_workers=2,
                    queue_size=1,
                ),
                timeout=5,
            )

    completed = asyncio.run(run())

    assert completed == len(discovered)
    assert sorted(inserted) == sorted([*LEGACY_URIS, V2_URI])
    assert sorted(uri for uri, _ in failures) == sorted(
        [UNSUPPORTED_URI, READ_FAILURE_URI]
    )
    assert sum(uri == UNSUPPORTED_URI for uri, _ in failures) == 1
    assert sum(uri == READ_FAILURE_URI for uri, _ in failures) == 1
    assert sorted(storage.reads) == sorted([*LEGACY_URIS, V2_URI, READ_FAILURE_URI])
