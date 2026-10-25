from __future__ import annotations

import base64
import json
from datetime import UTC, datetime
from pathlib import Path

import polars as pl
import pytest

from cluetooth_sync.pipeline import (
    ingest_scan_jsonl_bytes,
    insert_prepared_scans,
    prepare_scan_jsonl_bytes,
)


SCAN_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "scans.jsonl"
SCAN_FIXTURE_URI = "gs://test-bucket/scans.jsonl"


def _utc(*args: int) -> datetime:
    return datetime(*args, tzinfo=UTC)


LEGACY_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "legacy_0_0_3.jsonl"
LEGACY_FIXTURE_URI = (
    "gs://test-bucket/scans/"
    "2026-04-10T19-37-46.210Z_balexfbQ4yvHMTOqdix2cmS61Y-_0.0.3.jsonl.zst.encrypted"
)


def test_prepare_scan_jsonl_bytes() -> None:
    scans = prepare_scan_jsonl_bytes(SCAN_FIXTURE_PATH.read_bytes(), SCAN_FIXTURE_URI)

    assert scans.height == 2
    assert scans.schema["scanned_at"] == pl.Datetime(time_zone="UTC")
    assert scans.select("blob").to_series().to_list() == [
        SCAN_FIXTURE_URI,
        SCAN_FIXTURE_URI,
    ]

    rows = scans.select(
        "addr",
        "raw",
        "local_name",
        "tx_power",
        "is_connectable",
    ).rows(named=True)
    assert rows == [
        {
            "addr": "2C:FE:8B:28:BB:05",
            "raw": "0c09515420384232384242303502010607ff7335f9b833cc",
            "local_name": "QT 8B28BB05",
            "tx_power": -4,
            "is_connectable": True,
        },
        {
            "addr": "45:31:31:24:54:CB",
            "raw": "0201021bff7500021862a1ef88b77116fda9718ed8cc074742a3da5d66a6fa09ff7500f9de6daffc64",
            "local_name": None,
            "tx_power": None,
            "is_connectable": None,
        },
    ]


def test_prepare_legacy_scan_jsonl_bytes() -> None:
    scans = prepare_scan_jsonl_bytes(
        LEGACY_FIXTURE_PATH.read_bytes(),
        LEGACY_FIXTURE_URI,
    )

    assert scans.height == 2
    assert scans.schema["scanned_at"] == pl.Datetime(time_zone="UTC")
    assert scans.select("blob").to_series().to_list() == [
        LEGACY_FIXTURE_URI,
        LEGACY_FIXTURE_URI,
    ]

    rows = scans.select(
        "addr",
        "raw",
        "local_name",
        "tx_power",
        "is_connectable",
    ).rows(named=True)
    assert rows == [
        {
            "addr": "2C:FE:8B:28:BB:05",
            "raw": "0c09515420384232384242303502010607ff7335f9b833cc",
            "local_name": "QT 8B28BB05",
            "tx_power": None,
            "is_connectable": None,
        },
        {
            "addr": "45:31:31:24:54:CB",
            "raw": "0201021bff7500021862a1ef88b77116fda9718ed8cc074742a3da5d66a6fa09ff7500f9de6daffc64",
            "local_name": None,
            "tx_power": None,
            "is_connectable": None,
        },
    ]


@pytest.mark.parametrize("version", ["0.0.2", "0.0.4", "0.0.4-debug"])
def test_prepare_legacy_scan_jsonl_bytes_for_compatible_versions(version: str) -> None:
    scans = prepare_scan_jsonl_bytes(
        LEGACY_FIXTURE_PATH.read_bytes(),
        f"gs://test-bucket/scans/2025-08-21T18-49-02.128Z_device_{version}.jsonl.zst.encrypted",
    )

    assert scans.height == 2
    assert scans.select("raw").to_series().to_list() == [
        "0c09515420384232384242303502010607ff7335f9b833cc",
        "0201021bff7500021862a1ef88b77116fda9718ed8cc074742a3da5d66a6fa09ff7500f9de6daffc64",
    ]


def test_prepare_scan_jsonl_bytes_nulls_unavailable_rssi() -> None:
    payload = (
        b'{"addr":"aa:bb:cc:dd:ee:ff","rssi":127,'
        b'"scanned_at":"2025-06-12T22:47:48.989Z","raw":"020106",'
        b'"local_name":null,"tx_power":null,"is_connectable":null,'
        b'"lat":null,"lon":null,"accuracy":null}\n'
    )

    scans = prepare_scan_jsonl_bytes(payload, "gs://test-bucket/scans.jsonl")

    assert scans.select("rssi").item() is None


def test_prepare_scan_jsonl_bytes_strips_local_name_nul_bytes() -> None:
    payload = (
        b'{"addr":"aa:bb:cc:dd:ee:ff","rssi":-42,'
        b'"scanned_at":"2025-06-12T22:47:48.989Z","raw":"020106",'
        b'"local_name":"Fo\\u0000o\\u0000","tx_power":null,"is_connectable":null,'
        b'"lat":null,"lon":null,"accuracy":null}\n'
    )

    scans = prepare_scan_jsonl_bytes(payload, "gs://test-bucket/scans.jsonl")

    assert scans.select("local_name").item() == "Foo"


def test_prepare_legacy_scan_jsonl_bytes_trims_name_null_padding() -> None:
    payload = (
        b'{"mac":"aa:bb:cc:dd:ee:ff","rssi":-42,'
        b'"timestamp":"2025-06-12T22:47:48.989Z",'
        b'"lat":null,"lon":null,"accuracy":null,'
        b'"raw":"BglGb28AAA=="}\n'
    )

    scans = prepare_scan_jsonl_bytes(payload, LEGACY_FIXTURE_URI)

    assert scans.select("local_name").item() == "Foo"


def test_insert_prepared_scans(database_url: str) -> None:
    scans = prepare_scan_jsonl_bytes(SCAN_FIXTURE_PATH.read_bytes(), SCAN_FIXTURE_URI)

    inserted = insert_prepared_scans(database_url, scans)

    assert inserted == 2

    minutes = pl.read_database_uri(
        """
        select
          m.minute,
          m.addr::text as addr,
          m.sightings,
          m.first_seen,
          m.last_seen,
          m.rssi_min,
          m.rssi_max,
          m.best_accuracy,
          m.tx_power,
          m.is_connectable,
          st_y(m.location::geometry) as lat,
          st_x(m.location::geometry) as lon,
          f.local_name,
          m.blob_counts::text as blob_counts,
          m.uploader_id,
          m.session_id
        from sighting_minutes m
        join payload_fields f on f.payload_id = m.payload_id
        where m.addr in ('2c:fe:8b:28:bb:05', '45:31:31:24:54:cb')
          and m.minute >= timestamp '2025-06-12 22:00:00+00'
          and m.minute < timestamp '2025-06-13 00:00:00+00'
        order by m.addr::text
        """,
        database_url,
        engine="adbc",
    ).to_dicts()

    for row in minutes:
        provenance = json.loads(row.pop("blob_counts"))
        assert len(provenance) == 1
        assert sum(provenance.values()) == row["sightings"]
    assert [row.pop("uploader_id") for row in minutes] == [None, None]
    assert [row.pop("session_id") for row in minutes] == [None, None]
    assert minutes == [
        {
            "minute": _utc(2025, 6, 12, 22, 47),
            "addr": "2c:fe:8b:28:bb:05",
            "sightings": 1,
            "first_seen": _utc(2025, 6, 12, 22, 47, 48, 989000),
            "last_seen": _utc(2025, 6, 12, 22, 47, 48, 989000),
            "rssi_min": -97,
            "rssi_max": -97,
            "best_accuracy": pytest.approx(2.383),
            "tx_power": -4,
            "is_connectable": True,
            "lat": pytest.approx(32.7441436),
            "lon": pytest.approx(-117.1615848),
            "local_name": "QT 8B28BB05",
        },
        {
            "minute": _utc(2025, 6, 12, 23, 1),
            "addr": "45:31:31:24:54:cb",
            "sightings": 1,
            "first_seen": _utc(2025, 6, 12, 23, 1, 2),
            "last_seen": _utc(2025, 6, 12, 23, 1, 2),
            "rssi_min": -84,
            "rssi_max": -84,
            "best_accuracy": pytest.approx(2.266),
            "tx_power": None,
            "is_connectable": None,
            "lat": pytest.approx(32.74408),
            "lon": pytest.approx(-117.1616317),
            "local_name": None,
        },
    ]

    ledger = pl.read_database_uri(
        "select uri, success, uploader_id from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [SCAN_FIXTURE_URI]},
    ).to_dicts()
    assert ledger == [{"uri": SCAN_FIXTURE_URI, "success": True, "uploader_id": None}]

    structures = pl.read_database_uri(
        """
        select s.ad_type, encode(s.data, 'hex') as data
        from ad_structures s
        where s.id in (
          select unnest(p.structure_ids)
          from payloads p
          join sighting_minutes m on m.payload_id = p.id
          where m.addr = '2c:fe:8b:28:bb:05'
            and m.minute >= timestamp '2025-06-12 22:00:00+00'
            and m.minute < timestamp '2025-06-13 00:00:00+00'
        )
        order by s.ad_type, s.data
        """,
        database_url,
        engine="adbc",
    ).to_dicts()
    assert structures == [
        {"ad_type": 1, "data": "06"},
        {"ad_type": 9, "data": "5154203842323842423035"},
        {"ad_type": 255, "data": "7335f9b833cc"},
    ]


def test_ingest_scan_jsonl_bytes(database_url: str) -> None:
    inserted = ingest_scan_jsonl_bytes(
        database_url=database_url,
        blob_bytes=SCAN_FIXTURE_PATH.read_bytes(),
        gcs_blob_uri="gs://test-bucket/other-scans.jsonl",
    )

    assert inserted == 2


def test_ingest_legacy_sample_to_new_db(database_url: str) -> None:
    inserted = ingest_scan_jsonl_bytes(
        database_url=database_url,
        blob_bytes=LEGACY_FIXTURE_PATH.read_bytes(),
        gcs_blob_uri=LEGACY_FIXTURE_URI,
    )

    assert inserted == 2

    counts = pl.read_database_uri(
        """
        with legacy_minutes as (
          select m.*
          from sighting_minutes m
          join blobs b on b.id = any (m.blob_ids)
          where b.uri = $1
        ),
        legacy_payloads as (select distinct payload_id from legacy_minutes)
        select
          (select count(*)::int from legacy_minutes) as minute_count,
          (select coalesce(sum(sightings), 0)::int from legacy_minutes) as sighting_count,
          (select count(*)::int from legacy_payloads) as payload_count,
          (
            select count(distinct s.id)::int
            from legacy_payloads lp
            join payloads p on p.id = lp.payload_id
            cross join lateral unnest(p.structure_ids) as structure(structure_id)
            join ad_structures s on s.id = structure.structure_id
          ) as structure_count,
          (
            select uploader
            from blobs b
            join uploaders u on u.id = b.uploader_id
            where b.uri = $1
          ) as uploader
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [LEGACY_FIXTURE_URI]},
    ).to_dicts()

    assert counts == [
        {
            "minute_count": 2,
            "sighting_count": 2,
            "structure_count": 6,
            "payload_count": 2,
            "uploader": "balexfbQ4yvHMTOqdix2cmS61Y-",
        }
    ]

    app_versions = pl.read_database_uri(
        "select app_version, success from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [LEGACY_FIXTURE_URI]},
    ).to_dicts()
    assert app_versions == [{"app_version": "0.0.3", "success": True}]


def test_legacy_ingest_derives_the_name_from_raw(database_url: str) -> None:
    """Names come from the raw advertisement, not from a stored per-row name."""
    blob_uri = "gs://test-bucket/scans/2026-04-11T00-00-00.000Z_masked_0.0.4.jsonl.zst.encrypted"
    raw = bytes.fromhex("0c09515420384232384242424202")
    payload = (
        json.dumps(
            {
                "mac": "AA:BB:CC:DD:EE:01",
                "rssi": -50,
                "timestamp": "2026-04-11T00:00:00.000Z",
                "lat": None,
                "lon": None,
                "accuracy": None,
                # Legacy objects carry the advertisement as base64.
                "raw": base64.b64encode(raw).decode(),
            }
        ).encode()
        + b"\n"
    )

    assert ingest_scan_jsonl_bytes(database_url, payload, blob_uri) == 1

    names = pl.read_database_uri(
        """
        select f.local_name
        from sighting_minutes m
        join payload_fields f on f.payload_id = m.payload_id
        join blobs b on b.id = any (m.blob_ids)
        where b.uri = $1
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [blob_uri]},
    ).to_dicts()

    assert names == [{"local_name": "QT 8B28BBBB"}]
