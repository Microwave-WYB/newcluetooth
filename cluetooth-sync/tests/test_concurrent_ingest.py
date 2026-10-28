"""Two sync processes must not ingest (or fail) the same blob twice."""

from __future__ import annotations

import io
import json
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from typing import Any

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox

from cluetooth_sync.pipeline import gatt_ingest, ingest
from cluetooth_sync.pipeline.ingest import mark_blob_failed
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob
from cluetooth_sync.pipeline.payload_v3 import PAYLOAD_V3_POLARS_SCHEMA

PRIVATE_KEY = PrivateKey(bytes(range(32)))
GATT_URI = "gs://test-bucket/gatt/v1/2025/03/24/0195c920-7c00-7abc-8def-0123456789a1.json.encrypted"
V3_URI = "gs://test-bucket/scans/v3/2025/03/24/0195c920-7c00-7abc-8def-0123456789a2.parquet.encrypted"


def _encrypt(plaintext: bytes) -> bytes:
    return SealedBox(PRIVATE_KEY.public_key).encrypt(plaintext)


def _gatt_object() -> bytes:
    return json.dumps(
        {
            "schema": "gatt/v1",
            "id": GATT_URI.rsplit("/", 1)[-1].removesuffix(".json.encrypted"),
            "target": {"addr": "AA:00:00:05:00:01"},
            "started_at": "2026-10-27T12:00:01.000Z",
            "outcome": "complete",
            "stage_reached": "disconnect",
            "services": [
                {
                    "uuid": "180A",
                    "characteristics": [
                        {
                            "uuid": "2A29",
                            "properties": ["read"],
                            "value": {"hex": "00", "status": "ok"},
                        }
                    ],
                }
            ],
        }
    ).encode()


def _v3_file() -> bytes:
    rows = [
        {
            "addr": "AA:00:00:05:00:02",
            "rssi": -60 - i,
            "scanned_at": datetime(2031, 5, 1, 12, 0, i, tzinfo=UTC),
            "raw": bytes.fromhex("0201060302f0fe"),
            "local_name": None,
            "tx_power": None,
            "is_connectable": True,
            "lat": None,
            "lon": None,
            "accuracy": None,
            "is_legacy": False,
            "primary_phy": 1,
            "secondary_phy": 2,
            "adv_sid": 1,
            "adv_tx_power": None,
            "periodic_interval": None,
            "data_status": 0,
            "addr_type": None,
        }
        for i in range(3)
    ]
    output = io.BytesIO()
    pl.DataFrame(rows, schema=PAYLOAD_V3_POLARS_SCHEMA).write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v3",
            "cluetooth.payload_id": V3_URI.rsplit("/", 1)[-1].removesuffix(
                ".parquet.encrypted"
            ),
        },
    )
    return output.getvalue()


def _query(database_url: str, sql: str, *parameters: Any) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        sql,
        database_url,
        engine="adbc",
        execute_options={"parameters": list(parameters)},
    ).to_dicts()


def _slow(monkeypatch: pytest.MonkeyPatch, module: Any) -> None:
    """Make the payload step slow so two ingests of one blob overlap."""
    real = module.store_payloads

    def slow(*args: Any, **kwargs: Any) -> None:
        time.sleep(1.0)
        real(*args, **kwargs)

    monkeypatch.setattr(module, "store_payloads", slow)


def _ingest_twice(database_url: str, blob: BlobBytes) -> list[int | None]:
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [
            pool.submit(_ingest_blob, database_url, bytes(PRIVATE_KEY), blob)
            for _ in range(2)
        ]
        return [future.result() for future in futures]


def test_concurrent_gatt_ingest_of_one_object_stores_it_once(
    database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    _slow(monkeypatch, gatt_ingest)
    results = _ingest_twice(database_url, BlobBytes(GATT_URI, _encrypt(_gatt_object())))

    assert sorted(r for r in results if r is not None) == [0, 1]
    assert None not in results
    assert _query(
        database_url,
        "select b.success, b.error, "
        "(select count(*) from gatt_discoveries d where d.blob_id = b.id) as discoveries, "
        "(select count(*) from gatt_attributes a join gatt_discoveries d "
        "on d.id = a.discovery_id where d.blob_id = b.id) as attributes "
        "from blobs b where b.uri = $1",
        GATT_URI,
    ) == [{"success": True, "error": None, "discoveries": 1, "attributes": 2}]


def test_concurrent_scan_ingest_of_one_object_counts_sightings_once(
    database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    _slow(monkeypatch, ingest)
    results = _ingest_twice(database_url, BlobBytes(V3_URI, _encrypt(_v3_file())))

    assert sorted(r for r in results if r is not None) == [0, 3]
    assert None not in results
    assert _query(
        database_url,
        "select b.success, coalesce(sum(m.sightings), 0)::int as sightings "
        "from blobs b left join sighting_minutes m on b.id = any (m.blob_ids) "
        "where b.uri = $1 group by b.success",
        V3_URI,
    ) == [{"success": True, "sightings": 3}]


def test_a_failure_record_never_overwrites_a_success(database_url: str) -> None:
    uri = GATT_URI.replace("a1.json", "a3.json")
    gatt = json.loads(_gatt_object())
    gatt["id"] = uri.rsplit("/", 1)[-1].removesuffix(".json.encrypted")
    blob = BlobBytes(uri, _encrypt(json.dumps(gatt).encode()))
    assert _ingest_blob(database_url, bytes(PRIVATE_KEY), blob) == 1

    sql = "select success, attempts, error, next_attempt_at from blobs where uri = $1"
    before = _query(database_url, sql, uri)
    assert before[0]["success"] is True

    mark_blob_failed(database_url, uri, RuntimeError("late failure"))
    assert _query(database_url, sql, uri) == before

    # A blob that never succeeded is still recorded as failed, with backoff.
    other = "gs://test-bucket/gatt/v1/2025/03/24/never-ingested.json.encrypted"
    mark_blob_failed(database_url, other, RuntimeError("boom"))
    mark_blob_failed(database_url, other, RuntimeError("boom again"))
    assert _query(
        database_url, "select success, attempts, error from blobs where uri = $1", other
    ) == [{"success": False, "attempts": 2, "error": "boom again"}]
