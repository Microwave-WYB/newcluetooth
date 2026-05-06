from __future__ import annotations

import json
from pathlib import Path

import polars as pl
import pytest

from cluetooth_sync.pipeline import (
    ingest_scan_jsonl_bytes,
    insert_builtin_ad_structures,
    insert_prepared_scans,
    project_advs,
    prepare_scan_jsonl_bytes,
)


SCAN_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "scans.jsonl"
SCAN_FIXTURE_URI = "gs://test-bucket/scans.jsonl"
LEGACY_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "legacy_0_0_3.jsonl"
LEGACY_FIXTURE_URI = (
    "gs://test-bucket/scans/"
    "2026-04-10T19-37-46.210Z_balexfbQ4yvHMTOqdix2cmS61Y-_0.0.3.jsonl.zst.encrypted"
)
APPLE_ADV_RAW = "02010605094e616d6505ff4c000102"
SERVICE_ADV_RAW = (
    "03030d18"
    "050578563412"
    "1107fb349b5f80000080001000000d180000"
    "04160f1801"
    "06204433221101"
    "1221fb349b5f80000080001000000d18000001"
)


def _insert_scan(
    database_url: str,
    *,
    blob_uri: str,
    addr: str,
    raw: str,
    scanned_at: str,
    rssi: int | None = None,
    local_name: str | None = None,
    lat: float | None = None,
    lon: float | None = None,
    accuracy: float | None = None,
) -> int:
    payload = {
        "addr": addr,
        "rssi": rssi,
        "scanned_at": scanned_at,
        "raw": raw,
        "local_name": local_name,
        "tx_power": None,
        "is_connectable": None,
        "lat": lat,
        "lon": lon,
        "accuracy": accuracy,
    }
    scans = prepare_scan_jsonl_bytes(
        (json.dumps(payload) + "\n").encode(),
        blob_uri,
    )
    insert_prepared_scans(database_url, scans)

    scan_id = (
        pl.read_database_uri(
            """
        select max(id)::bigint as id
        from scans
        where blob = $1
          and addr = cast($2 as macaddr)
          and raw = decode($3, 'hex')
          and scanned_at = cast($4 as timestamptz)
        """,
            database_url,
            engine="adbc",
            execute_options={"parameters": [blob_uri, addr, raw, scanned_at]},
        )
        .select("id")
        .item()
    )
    if scan_id is None:
        raise RuntimeError("scan insert did not return an id")
    return int(scan_id)


def _advs_scans_through_id(database_url: str) -> int:
    return int(
        pl.read_database_uri(
            """
            select scans_through_id
            from projection_state
            where name = 'advs'
            """,
            database_url,
            engine="adbc",
        )
        .select("scans_through_id")
        .item()
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
    project_advs(database_url)
    projection_before = _advs_scans_through_id(database_url)
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
        where blob = $1
        order by addr::text
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [SCAN_FIXTURE_URI]},
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
        """
        select count(*)::int as count
        from adv_enrichments e
        join (
          select distinct addr, raw
          from scans
          where blob = $1
        ) s
          on s.addr = e.addr
         and s.raw = e.raw
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [SCAN_FIXTURE_URI]},
    )
    assert enrichment_count.select("count").item() == 0

    caught_up_advs = pl.read_database_uri(
        """
        select count(*)::int as count
        from advs a
        join scans s
          on s.addr = a.addr
         and s.raw = a.raw
        where s.blob = $1
          and a.scans_through_id >= s.id
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [SCAN_FIXTURE_URI]},
    )
    assert projection_before < (
        pl.read_database_uri(
            "select max(id)::bigint as id from scans where blob = $1",
            database_url,
            engine="adbc",
            execute_options={"parameters": [SCAN_FIXTURE_URI]},
        )
        .select("id")
        .item()
    )
    assert caught_up_advs.select("count").item() == 0

    blobs = pl.read_database_uri(
        "select uri, success from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [SCAN_FIXTURE_URI]},
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
        """
        select count(*)::int as count
        from adv_enrichments e
        join (
          select distinct addr, raw
          from scans
          where blob = $1
        ) s
          on s.addr = e.addr
         and s.raw = e.raw
        where e.enrichment_kind = 'builtin'
          and e.enrichment_id = 'ad_structures'
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [fixture_uri]},
    )
    assert enrichment_count.select("count").item() == 2


def test_advs_exposes_sig_query_fields(database_url: str) -> None:
    fixture_uri = "gs://test-bucket/adv-query-fields.jsonl"
    scans = prepare_scan_jsonl_bytes(SCAN_FIXTURE_PATH.read_bytes(), fixture_uri)
    inserted = insert_prepared_scans(database_url, scans)

    assert inserted == 2

    project_advs(database_url)

    rows = pl.read_database_uri(
        """
        select
          a.addr::text as addr,
          to_json(a.adv_types)::text as adv_types,
          to_json(a.manufacturer_ids)::text as manufacturer_ids,
          to_json(a.service_uuids)::text as service_uuids,
          to_json(a.service_data_uuids)::text as service_data_uuids
        from advs a
        join (
          select distinct addr, raw
          from scans
          where blob = $1
        ) s
          on s.addr = a.addr
         and s.raw = a.raw
        order by a.addr::text
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [fixture_uri]},
    ).to_dicts()

    for row in rows:
        row["adv_types"] = json.loads(row["adv_types"])
        row["manufacturer_ids"] = json.loads(row["manufacturer_ids"])
        row["service_uuids"] = json.loads(row["service_uuids"])
        row["service_data_uuids"] = json.loads(row["service_data_uuids"])

    assert rows == [
        {
            "addr": "2c:fe:8b:28:bb:05",
            "adv_types": [9, 1, 255],
            "manufacturer_ids": [13683],
            "service_uuids": [],
            "service_data_uuids": [],
        },
        {
            "addr": "45:31:31:24:54:cb",
            "adv_types": [1, 255, 255],
            "manufacturer_ids": [117],
            "service_uuids": [],
            "service_data_uuids": [],
        },
    ]


def test_project_advs_high_watermark_and_catchup(database_url: str) -> None:
    project_advs(database_url)
    initial_scans_through_id = _advs_scans_through_id(database_url)
    blob_uri = "gs://test-bucket/projection/high-watermark.jsonl"
    addr = "AA:BB:CC:00:00:01"
    raw = APPLE_ADV_RAW

    first_id = _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=addr,
        raw=raw,
        scanned_at="2026-01-01T00:00:00Z",
        rssi=-50,
        local_name="Old Name",
    )

    project_advs(database_url)
    first_scans_through_id = _advs_scans_through_id(database_url)

    assert first_scans_through_id >= first_id
    assert first_scans_through_id > initial_scans_through_id

    first_row = pl.read_database_uri(
        """
        select
          scans_through_id,
          scans_count,
          rssi_min,
          local_name
        from advs
        where addr = cast($1 as macaddr)
          and raw = decode($2, 'hex')
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [addr, raw]},
    ).to_dicts()[0]

    assert first_row == {
        "scans_through_id": first_scans_through_id,
        "scans_count": 1,
        "rssi_min": -50,
        "local_name": "Old Name",
    }

    second_id = _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=addr,
        raw=raw,
        scanned_at="2026-01-01T00:01:00Z",
        rssi=-80,
        local_name="New Name",
    )

    stale_row = pl.read_database_uri(
        """
        select
          scans_through_id,
          scans_count
        from advs
        where addr = cast($1 as macaddr)
          and raw = decode($2, 'hex')
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [addr, raw]},
    ).to_dicts()[0]

    assert stale_row == {
        "scans_through_id": first_scans_through_id,
        "scans_count": 1,
    }
    assert stale_row["scans_through_id"] < second_id

    project_advs(database_url)
    second_scans_through_id = _advs_scans_through_id(database_url)

    assert second_scans_through_id >= second_id
    assert second_scans_through_id > first_scans_through_id

    caught_up_row = pl.read_database_uri(
        """
        select
          scans_through_id,
          scans_count,
          rssi_min,
          local_name
        from advs
        where addr = cast($1 as macaddr)
          and raw = decode($2, 'hex')
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [addr, raw]},
    ).to_dicts()[0]

    assert caught_up_row == {
        "scans_through_id": second_scans_through_id,
        "scans_count": 2,
        "rssi_min": -80,
        "local_name": "New Name",
    }


def test_project_advs_no_op_when_no_new_scans(database_url: str) -> None:
    project_advs(database_url)
    first_scans_through_id = _advs_scans_through_id(database_url)
    first_adv_count = (
        pl.read_database_uri(
            "select count(*)::int as count from advs",
            database_url,
            engine="adbc",
        )
        .select("count")
        .item()
    )

    project_advs(database_url)
    second_scans_through_id = _advs_scans_through_id(database_url)
    second_adv_count = (
        pl.read_database_uri(
            "select count(*)::int as count from advs",
            database_url,
            engine="adbc",
        )
        .select("count")
        .item()
    )

    assert second_scans_through_id == first_scans_through_id
    assert second_adv_count == first_adv_count


def test_project_advs_extracts_apple_and_service_uuid_fields(
    database_url: str,
) -> None:
    project_advs(database_url)
    blob_uri = "gs://test-bucket/projection/sig-fields.jsonl"
    apple_addr = "AA:BB:CC:00:00:02"
    service_addr = "AA:BB:CC:00:00:03"

    _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=apple_addr,
        raw=APPLE_ADV_RAW,
        scanned_at="2026-01-02T00:00:00Z",
        rssi=-40,
    )
    _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=service_addr,
        raw=SERVICE_ADV_RAW,
        scanned_at="2026-01-02T00:00:01Z",
        rssi=-41,
    )

    project_advs(database_url)

    rows = pl.read_database_uri(
        """
        select
          addr::text as addr,
          to_json(adv_types)::text as adv_types,
          to_json(manufacturer_ids)::text as manufacturer_ids,
          to_json(service_uuids)::text as service_uuids,
          to_json(service_data_uuids)::text as service_data_uuids
        from advs
        where addr in (cast($1 as macaddr), cast($2 as macaddr))
        order by addr::text
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [apple_addr, service_addr]},
    ).to_dicts()

    for row in rows:
        row["adv_types"] = json.loads(row["adv_types"])
        row["manufacturer_ids"] = json.loads(row["manufacturer_ids"])
        row["service_uuids"] = json.loads(row["service_uuids"])
        row["service_data_uuids"] = json.loads(row["service_data_uuids"])

    assert rows == [
        {
            "addr": "aa:bb:cc:00:00:02",
            "adv_types": [1, 9, 255],
            "manufacturer_ids": [76],
            "service_uuids": [],
            "service_data_uuids": [],
        },
        {
            "addr": "aa:bb:cc:00:00:03",
            "adv_types": [3, 5, 7, 22, 32, 33],
            "manufacturer_ids": [],
            "service_uuids": [
                "0000180d-0000-1000-8000-00805f9b34fb",
                "12345678",
                "180d",
            ],
            "service_data_uuids": [
                "0000180d-0000-1000-8000-00805f9b34fb",
                "11223344",
                "180f",
            ],
        },
    ]


def test_project_advs_location_and_local_name_rollup(database_url: str) -> None:
    project_advs(database_url)
    blob_uri = "gs://test-bucket/projection/location.jsonl"
    addr = "AA:BB:CC:00:00:04"
    raw = "020106"

    _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=addr,
        raw=raw,
        scanned_at="2026-01-03T00:00:00Z",
        rssi=-50,
        local_name="Old",
        lat=0.0,
        lon=0.0,
        accuracy=5.0,
    )
    _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=addr,
        raw=raw,
        scanned_at="2026-01-03T00:01:00Z",
        rssi=-60,
        lat=0.0,
        lon=0.002,
        accuracy=5.0,
    )
    _insert_scan(
        database_url,
        blob_uri=blob_uri,
        addr=addr,
        raw=raw,
        scanned_at="2026-01-03T00:02:00Z",
        rssi=-55,
        local_name="New",
    )

    project_advs(database_url)

    row = pl.read_database_uri(
        """
        select
          scans_count,
          rssi_min,
          centroid_lat,
          centroid_lon,
          st_y(centroid) as centroid_lat_from_geom,
          st_x(centroid) as centroid_lon_from_geom,
          location_count,
          min_lat,
          max_lat,
          min_lon,
          max_lon,
          st_ymin(box3d(bbox)) as min_lat_from_bbox,
          st_ymax(box3d(bbox)) as max_lat_from_bbox,
          st_xmin(box3d(bbox)) as min_lon_from_bbox,
          st_xmax(box3d(bbox)) as max_lon_from_bbox,
          local_name
        from advs
        where addr = cast($1 as macaddr)
          and raw = decode($2, 'hex')
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [addr, raw]},
    ).to_dicts()[0]

    assert row["scans_count"] == 3
    assert row["rssi_min"] == -60
    assert row["centroid_lat"] == pytest.approx(0.0)
    assert row["centroid_lon"] == pytest.approx(0.001)
    assert row["centroid_lat_from_geom"] == pytest.approx(row["centroid_lat"])
    assert row["centroid_lon_from_geom"] == pytest.approx(row["centroid_lon"])
    assert row["location_count"] == 2
    assert row["min_lat"] == pytest.approx(0.0)
    assert row["max_lat"] == pytest.approx(0.0)
    assert row["min_lon"] == pytest.approx(0.0)
    assert row["max_lon"] == pytest.approx(0.002)
    assert row["min_lat_from_bbox"] == pytest.approx(row["min_lat"])
    assert row["max_lat_from_bbox"] == pytest.approx(row["max_lat"])
    assert row["min_lon_from_bbox"] == pytest.approx(row["min_lon"])
    assert row["max_lon_from_bbox"] == pytest.approx(row["max_lon"])
    assert row["local_name"] == "New"

    radius_column_count = (
        pl.read_database_uri(
            """
        select count(*)::int as count
        from information_schema.columns
        where table_name = 'advs'
          and column_name = 'radius'
        """,
            database_url,
            engine="adbc",
        )
        .select("count")
        .item()
    )
    assert radius_column_count == 0


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
    project_advs(database_url)

    counts = pl.read_database_uri(
        """
        with legacy_scans as (
          select addr, raw
          from scans
          where blob = $1
        )
        select
          (
            select count(*)::int
            from scans
            where blob = $1
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
            from advs a
            join (select distinct addr, raw from legacy_scans) s
              on s.addr = a.addr
             and s.raw = a.raw
          ) as adv_count,
          (
            select count(*)::int
            from advs a
            join (select distinct addr, raw from legacy_scans) s
              on s.addr = a.addr
             and s.raw = a.raw
            where a.scans_through_id >= (
              select max(id)
              from scans
              where blob = $1
            )
          ) as caught_up_adv_count
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [LEGACY_FIXTURE_URI]},
    )

    assert counts.to_dicts() == [
        {
            "scan_count": 2,
            "builtin_enrichment_count": 2,
            "adv_count": 2,
            "caught_up_adv_count": 2,
        }
    ]
