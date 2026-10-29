"""Objects written by the real producer (Rust core and Kotlin GATT engine).

The files are a snapshot of the core branch's `test-fixtures/` (shared producer
fixtures); the sync side must accept every one of them.
"""

from __future__ import annotations

import json
from pathlib import Path

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox

from cluetooth_sync.pipeline import (
    PayloadRouteKind,
    parse_gatt_v1_bytes,
    prepare_payload_v3_parquet_bytes,
    route_payload_uri,
)
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob

ROOT = Path(__file__).parent / "fixtures/producer-v3"
GATT_FILES = sorted((ROOT / "gatt-v1").rglob("*.json"))
SCAN_FILES = sorted((ROOT / "scans-v3").rglob("*.parquet"))
PRIVATE_KEY = PrivateKey(bytes(range(32)))


def _uri(path: Path, root: str, suffix: str) -> str:
    relative = path.relative_to(ROOT / root).as_posix().removesuffix(suffix)
    return f"gs://test-bucket/{'gatt/v1' if root == 'gatt-v1' else 'scans/v3'}/{relative}{suffix}.encrypted"


def _encrypt(data: bytes) -> bytes:
    return SealedBox(PRIVATE_KEY.public_key).encrypt(data)


def test_the_fixture_snapshot_is_present() -> None:
    assert len(GATT_FILES) >= 3 and len(SCAN_FILES) >= 1


@pytest.mark.parametrize("path", GATT_FILES, ids=lambda p: p.stem[-4:])
def test_every_producer_gatt_object_is_ingested(path: Path, database_url: str) -> None:
    uri = _uri(path, "gatt-v1", ".json")
    route = route_payload_uri(uri)
    assert route.kind is PayloadRouteKind.GATT_V1_JSON
    discovery = parse_gatt_v1_bytes(path.read_bytes(), route)
    document = json.loads(path.read_text())
    assert discovery.outcome == document["outcome"]

    assert (
        _ingest_blob(
            database_url,
            bytes(PRIVATE_KEY),
            BlobBytes(uri, _encrypt(path.read_bytes())),
        )
        == 1
    )
    stored = pl.read_database_uri(
        "select count(*) as n from gatt_discoveries d join blobs b on b.id = d.blob_id "
        "where b.uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    )
    assert stored.item() == 1


@pytest.mark.parametrize("path", SCAN_FILES, ids=lambda p: p.stem[-4:])
def test_every_producer_scan_v3_file_is_ingested(path: Path, database_url: str) -> None:
    uri = _uri(path, "scans-v3", ".parquet")
    route = route_payload_uri(uri)
    assert route.kind is PayloadRouteKind.PAYLOAD_V3_PARQUET
    prepared, meta = prepare_payload_v3_parquet_bytes(path.read_bytes(), route)
    assert prepared.height == pl.read_parquet(path).height
    assert meta["device_model"] == "Pixel 8"

    assert (
        _ingest_blob(
            database_url,
            bytes(PRIVATE_KEY),
            BlobBytes(uri, _encrypt(path.read_bytes())),
        )
        == prepared.height
    )
    ledger = pl.read_database_uri(
        "select b.success, b.app_version, b.session_id::text as session_id, "
        "b.meta ->> 'device_model' as model, "
        "(select count(*) from sighting_minutes m where b.id = any (m.blob_ids)) as minute_rows "
        "from blobs b where b.uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).row(0, named=True)
    assert ledger == {
        "success": True,
        "app_version": meta["app_version"],
        "session_id": meta["scan_session_id"],
        "model": "Pixel 8",
        # One address appears twice in the same minute with different PDU fields.
        "minute_rows": prepared.height,
    }
