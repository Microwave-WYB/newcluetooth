from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

import polars as pl

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.backfill import (
    backfill_minutes,
    run_backfill,
    verify_backfill,
)


def _old_scan(
    database_url: str,
    *,
    blob_uri: str,
    addr: str,
    scanned_at: str,
    raw: str,
    rssi: int | None = None,
    local_name: str | None = None,
    tx_power: int | None = None,
    is_connectable: bool | None = None,
    lat: float | None = None,
    lon: float | None = None,
    accuracy: float | None = None,
) -> int:
    """Insert one legacy per-sighting row (ADBC cannot bind null parameters)."""
    with db.session(database_url) as session:
        session.cursor.execute(
            "insert into blobs (uri) values ($1) on conflict (uri) do nothing",
            [blob_uri],
        )
        session.cursor.execute(
            """
            insert into scans_old (
              blob, addr, scanned_at, raw, rssi, local_name, tx_power,
              is_connectable, lat, lon, accuracy
            )
            values (
              $1, cast($2 as macaddr), cast($3 as timestamptz), decode($4, 'hex'),
              nullif($5::text, '')::smallint, nullif($6::text, '')::text,
              nullif($7::text, '')::smallint, nullif($8::text, '')::boolean,
              nullif($9::text, '')::float8, nullif($10::text, '')::float8,
              nullif($11::text, '')::float4
            )
            returning id
            """,
            [
                blob_uri,
                addr,
                scanned_at,
                raw,
                "" if rssi is None else str(rssi),
                "" if local_name is None else local_name,
                "" if tx_power is None else str(tx_power),
                "" if is_connectable is None else str(is_connectable).lower(),
                "" if lat is None else repr(lat),
                "" if lon is None else repr(lon),
                "" if accuracy is None else repr(accuracy),
            ],
        )
        return int(session.cursor.fetch_polars().item(0, "id"))


def _claim_scan_for_wigle(database_url: str, scan_id: int) -> int:
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            insert into wigle_upload_batches (status, filename)
            values ('uploaded', 'old-batch.csv')
            returning id
            """
        )
        batch_id = int(session.cursor.fetch_polars().item(0, "id"))
        session.cursor.execute(
            "insert into wigle_upload_batch_scans_old (batch_id, scan_id) values ($1, $2)",
            [batch_id, scan_id],
        )
    return batch_id


def _utc(*args: int) -> datetime:
    return datetime(*args, tzinfo=UTC)


def _minutes(database_url: str, day: str) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        f"""
        select
          m.minute,
          m.addr::text as addr,
          m.sightings,
          m.first_seen,
          m.last_seen,
          m.rssi_min,
          m.rssi_max,
          m.rssi_sum,
          m.rssi_count,
          m.tx_power,
          m.is_connectable,
          m.min_lat,
          m.max_lat,
          m.min_lon,
          m.max_lon,
          encode(payload_raw(m.payload_id), 'hex') as raw,
          f.local_name
        from sighting_minutes m
        join payload_fields f on f.payload_id = m.payload_id
        where m.minute >= timestamp '{day} 00:00:00+00'
          and m.minute < timestamp '{day} 01:00:00+00'
        order by m.addr::text, m.minute
        """,
        database_url,
        engine="adbc",
    ).to_dicts()


def test_backfill_aggregates_old_scans_and_verifies(clean_database_url: str) -> None:
    raw = "02010605094e616d6505ff4c000102"
    first_blob = (
        "gs://test-bucket/scans/2026-06-01T00-00-00Z_device_0.0.3.jsonl.zst.encrypted"
    )
    second_blob = (
        "gs://test-bucket/scans/2026-06-01T00-05-00Z_device_0.0.3.jsonl.zst.encrypted"
    )

    first_scan = _old_scan(
        clean_database_url,
        blob_uri=first_blob,
        addr="AA:00:00:00:00:51",
        scanned_at="2026-06-01T00:00:00.000Z",
        raw=raw,
        rssi=-50,
        local_name="Name",
        tx_power=-4,
        is_connectable=True,
        lat=10.0,
        lon=20.0,
        accuracy=5.0,
    )
    _old_scan(
        clean_database_url,
        blob_uri=first_blob,
        addr="AA:00:00:00:00:51",
        scanned_at="2026-06-01T00:00:30.000Z",
        raw=raw,
        rssi=-70,
        lat=11.0,
        lon=21.0,
        accuracy=1.0,
    )
    _old_scan(
        clean_database_url,
        blob_uri=first_blob,
        addr="AA:00:00:00:00:51",
        scanned_at="2026-06-01T00:05:00.000Z",
        raw=raw,
        rssi=-60,
    )
    _old_scan(
        clean_database_url,
        blob_uri=second_blob,
        addr="AA:00:00:00:00:52",
        scanned_at="2026-06-01T00:00:10.000Z",
        raw=raw,
        rssi=-80,
    )
    batch_id = _claim_scan_for_wigle(clean_database_url, first_scan)

    result = run_backfill(clean_database_url, batch_size=2)

    assert result.rows == 4
    assert result.ok, [check.name for check in result.checks if not check.ok]

    assert _minutes(clean_database_url, "2026-06-01") == [
        {
            "minute": _utc(2026, 6, 1, 0, 0),
            "addr": "aa:00:00:00:00:51",
            "sightings": 2,
            "first_seen": _utc(2026, 6, 1, 0, 0, 0),
            "last_seen": _utc(2026, 6, 1, 0, 0, 30),
            "rssi_min": -70,
            "rssi_max": -50,
            "rssi_sum": -120,
            "rssi_count": 2,
            "tx_power": -4,
            "is_connectable": True,
            "min_lat": 10.0,
            "max_lat": 11.0,
            "min_lon": 20.0,
            "max_lon": 21.0,
            "raw": raw,
            "local_name": "Name",
        },
        {
            "minute": _utc(2026, 6, 1, 0, 5),
            "addr": "aa:00:00:00:00:51",
            "sightings": 1,
            "first_seen": _utc(2026, 6, 1, 0, 5, 0),
            "last_seen": _utc(2026, 6, 1, 0, 5, 0),
            "rssi_min": -60,
            "rssi_max": -60,
            "rssi_sum": -60,
            "rssi_count": 1,
            "tx_power": None,
            "is_connectable": None,
            "min_lat": None,
            "max_lat": None,
            "min_lon": None,
            "max_lon": None,
            "raw": raw,
            "local_name": "Name",
        },
        {
            "minute": _utc(2026, 6, 1, 0, 0),
            "addr": "aa:00:00:00:00:52",
            "sightings": 1,
            "first_seen": _utc(2026, 6, 1, 0, 0, 10),
            "last_seen": _utc(2026, 6, 1, 0, 0, 10),
            "rssi_min": -80,
            "rssi_max": -80,
            "rssi_sum": -80,
            "rssi_count": 1,
            "tx_power": None,
            "is_connectable": None,
            "min_lat": None,
            "max_lat": None,
            "min_lon": None,
            "max_lon": None,
            "raw": raw,
            "local_name": "Name",
        },
    ]

    payload_stats = pl.read_database_uri(
        """
        select p.sightings, p.first_seen, p.last_seen
        from payloads p
        where p.sha256 = sha256(decode($1, 'hex'))
        """,
        clean_database_url,
        engine="adbc",
        execute_options={"parameters": [raw]},
    ).to_dicts()
    assert payload_stats == [
        {
            "sightings": 4,
            "first_seen": _utc(2026, 6, 1, 0, 0, 0),
            "last_seen": _utc(2026, 6, 1, 0, 5, 0),
        }
    ]

    # The old per-scan WiGLE membership now points at the minute row.
    membership = pl.read_database_uri(
        """
        select bm.batch_id, m.addr::text as addr, m.minute
        from wigle_upload_batch_minutes bm
        join sighting_minutes m on m.id = bm.minute_id and m.minute = bm.minute
        """,
        clean_database_url,
        engine="adbc",
    ).to_dicts()
    assert membership == [
        {
            "batch_id": batch_id,
            "addr": "aa:00:00:00:00:51",
            "minute": _utc(2026, 6, 1, 0, 0),
        }
    ]

    # Legacy blobs get their producer identity from the object name.
    producers = pl.read_database_uri(
        """
        select u.uploader, b.app_version, count(*)::int as blobs
        from blobs b
        join uploaders u on u.id = b.uploader_id
        group by u.uploader, b.app_version
        """,
        clean_database_url,
        engine="adbc",
    ).to_dicts()
    assert producers == [{"uploader": "device", "app_version": "0.0.3", "blobs": 2}]

    progress = pl.read_database_uri(
        "select phase, through_id, rows, done from backfill_progress",
        clean_database_url,
        engine="adbc",
    ).to_dicts()
    assert progress == [{"phase": "minutes", "through_id": 4, "rows": 4, "done": True}]

    # A second run resumes at the end: nothing is merged twice.
    assert backfill_minutes(clean_database_url, batch_size=2) == 4
    assert len(_minutes(clean_database_url, "2026-06-01")) == 3

    verify_only = run_backfill(clean_database_url, batch_size=2, verify_only=True)
    assert verify_only.rows == 0
    assert verify_only.ok


def test_backfill_uses_one_transaction_per_id_range(clean_database_url: str) -> None:
    for index in range(3):
        _old_scan(
            clean_database_url,
            blob_uri=(
                "gs://test-bucket/scans/2026-06-05T00-00-0"
                f"{index}Z_device_0.0.3.jsonl.zst.encrypted"
            ),
            addr=f"AA:00:00:00:00:9{index}",
            scanned_at=f"2026-06-05T00:00:0{index}.000Z",
            raw="020106",
            rssi=-50 - index,
        )

    rows = backfill_minutes(clean_database_url, batch_size=1)

    assert rows == 3
    assert len(_minutes(clean_database_url, "2026-06-05")) == 3
    progress = pl.read_database_uri(
        "select through_id, rows, done from backfill_progress where phase = 'minutes'",
        clean_database_url,
        engine="adbc",
    ).to_dicts()
    assert progress == [{"through_id": 3, "rows": 3, "done": True}]


def test_verify_reports_a_missing_minute_row(clean_database_url: str) -> None:
    _old_scan(
        clean_database_url,
        blob_uri="gs://test-bucket/scans/2026-06-02T00-00-00Z_device_0.0.3.jsonl.zst.encrypted",
        addr="AA:00:00:00:00:61",
        scanned_at="2026-06-02T00:00:00.000Z",
        raw="02010605094e616d6505ff4c000103",
        rssi=-50,
    )
    assert run_backfill(clean_database_url, batch_size=100).ok

    with db.session(clean_database_url) as session:
        session.cursor.execute(
            """
            delete from sighting_minutes
            where addr = 'aa:00:00:00:00:61'
              and minute = timestamp '2026-06-02 00:00:00+00'
            """
        )

    checks = {check.name: check for check in verify_backfill(clean_database_url)}
    assert checks["01_total_sightings"].state == "FAILED"
    assert checks["07_minute_rows"].state == "FAILED"
    assert checks["12_info_default_partition_rows"].state == "info"
    assert not run_backfill(clean_database_url, batch_size=100, verify_only=True).ok


def test_backfill_of_an_empty_old_table_verifies_trivially(
    clean_database_url: str,
) -> None:
    result = run_backfill(clean_database_url, batch_size=100)

    assert result.rows == 0
    assert result.ok
    assert all(check.state in ("ok", "info") for check in result.checks)
