from __future__ import annotations

import json
from pathlib import Path

import adbc_driver_postgresql.dbapi as pg_dbapi
import polars as pl

from cluetooth_sync.pipeline import (
    ingest_scan_jsonl_bytes,
    insert_builtin_ad_structures,
    insert_prepared_scans,
    prepare_scan_jsonl_bytes,
)


SCAN_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "scans.jsonl"
SCAN_FIXTURE_URI = "gs://test-bucket/scans.jsonl"
LEGACY_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "legacy_0_0_3.jsonl"
LEGACY_FIXTURE_URI = (
    "gs://test-bucket/scans/"
    "2026-04-10T19-37-46.210Z_balexfbQ4yvHMTOqdix2cmS61Y-_0.0.3.jsonl.zst.encrypted"
)


def _refresh_rollups(database_url: str) -> None:
    connection = pg_dbapi.connect(database_url, autocommit=True)
    cursor = connection.cursor()
    try:
        for view_name in (
            "adv_observations",
            "advs",
            "devices",
            "payloads",
        ):
            cursor.execute(f"refresh materialized view {view_name}")
    finally:
        cursor.close()
        connection.close()


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


def test_prepare_legacy_0_0_2_scan_jsonl_bytes() -> None:
    scans = prepare_scan_jsonl_bytes(
        LEGACY_FIXTURE_PATH.read_bytes(),
        "gs://test-bucket/scans/2025-08-21T18-49-02.128Z_device_0.0.2.jsonl.zst.encrypted",
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
    inserted_scans = pl.read_database_uri(
        """
        select
          blob,
          addr::text as addr,
          local_name
        from scans
        order by addr::text
        """,
        database_url,
        engine="adbc",
    )

    assert inserted_scans.to_dicts() == [
        {
            "blob": SCAN_FIXTURE_URI,
            "addr": "2c:fe:8b:28:bb:05",
            "local_name": "QT 8B28BB05",
        },
        {
            "blob": SCAN_FIXTURE_URI,
            "addr": "45:31:31:24:54:cb",
            "local_name": None,
        },
    ]

    enrichment_count = pl.read_database_uri(
        "select count(*)::int as count from adv_enrichments",
        database_url,
        engine="adbc",
    )
    assert enrichment_count.select("count").item() == 0

    blobs = pl.read_database_uri(
        "select uri, success from blobs",
        database_url,
        engine="adbc",
    )
    assert blobs.to_dicts() == [{"uri": SCAN_FIXTURE_URI, "success": True}]


def test_insert_builtin_ad_structures(database_url: str) -> None:
    fixture_uri = "gs://test-bucket/enrichment-scans.jsonl"
    scans = prepare_scan_jsonl_bytes(SCAN_FIXTURE_PATH.read_bytes(), fixture_uri)
    inserted = insert_prepared_scans(database_url, scans)

    assert inserted == 2

    insert_builtin_ad_structures(database_url)

    inserted_scans = pl.read_database_uri(
        """
        select
          s.blob,
          s.addr::text as addr,
          s.local_name,
          e.data::text as adv
        from scans s
        join adv_enrichments e
          on e.addr = s.addr
         and e.raw = s.raw
         and e.enrichment_kind = 'builtin'
         and e.enrichment_id = 'ad_structures'
        where s.blob = $1
        order by s.addr::text
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [fixture_uri]},
    )
    inserted_scan_rows = inserted_scans.to_dicts()
    for row in inserted_scan_rows:
        row["adv"] = json.loads(row["adv"])

    assert inserted_scan_rows == [
        {
            "blob": fixture_uri,
            "addr": "2c:fe:8b:28:bb:05",
            "local_name": "QT 8B28BB05",
            "adv": [
                {"type": 9, "data": "5154203842323842423035"},
                {"type": 1, "data": "06"},
                {"type": 255, "data": "7335f9b833cc"},
            ],
        },
        {
            "blob": fixture_uri,
            "addr": "45:31:31:24:54:cb",
            "local_name": None,
            "adv": [
                {"type": 1, "data": "02"},
                {
                    "type": 255,
                    "data": "7500021862a1ef88b77116fda9718ed8cc074742a3da5d66a6fa",
                },
                {"type": 255, "data": "7500f9de6daffc64"},
            ],
        },
    ]

    insert_builtin_ad_structures(database_url)
    enrichment_count = pl.read_database_uri(
        "select count(*)::int as count from adv_enrichments",
        database_url,
        engine="adbc",
    )
    assert enrichment_count.select("count").item() == 2


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

    insert_builtin_ad_structures(database_url)
    _refresh_rollups(database_url)

    fixture_uri = LEGACY_FIXTURE_URI.replace("'", "''")
    counts = pl.read_database_uri(
        f"""
        with legacy_scans as (
          select addr, raw
          from scans
          where blob = '{fixture_uri}'
        )
        select
          (
            select count(*)::int
            from scans
            where blob = '{fixture_uri}'
          ) as scan_count,
          (
            select count(*)::int
            from adv_enrichments e
            join legacy_scans s
              on s.addr = e.addr
             and s.raw = e.raw
            where e.enrichment_kind = 'builtin'
              and e.enrichment_id = 'ad_structures'
          ) as builtin_enrichment_count,
          (
            select count(*)::int
            from adv_observations
            where blob = '{fixture_uri}'
          ) as adv_observation_count,
          (
            select count(*)::int
            from advs a
            join (select distinct addr, raw from legacy_scans) s
              on s.addr = a.addr
             and s.raw = a.raw
          ) as adv_count,
          (
            select count(*)::int
            from devices d
            join (select distinct addr from legacy_scans) s
              on s.addr = d.addr
          ) as device_count,
          (
            select count(*)::int
            from payloads p
            join (select distinct raw from legacy_scans) s
              on s.raw = p.raw
          ) as payload_count
        """,
        database_url,
        engine="adbc",
    )

    assert counts.to_dicts() == [
        {
            "scan_count": 2,
            "builtin_enrichment_count": 2,
            "adv_observation_count": 2,
            "adv_count": 2,
            "device_count": 2,
            "payload_count": 2,
        }
    ]
