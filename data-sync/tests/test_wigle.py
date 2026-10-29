from __future__ import annotations

import csv
import io
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox
from typer.testing import CliRunner

import cluetooth_sync.cli as cli
import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline import insert_prepared_scans, prepare_scan_jsonl_bytes
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob
from cluetooth_sync.pipeline.payload_v3 import PAYLOAD_V3_POLARS_SCHEMA
from cluetooth_sync.wigle import (
    WIGLE_CSV_HEADER,
    build_wigle_auth_header,
    count_pending_wigle_rows,
    create_wigle_upload_batch,
    export_wigle_batch_csv,
    write_wigle_csv,
)


APPLE_ADV_RAW = "02010605094e616d6505ff4c000102"


def test_build_wigle_auth_header_uses_api_key_only() -> None:
    assert build_wigle_auth_header(api_key="encoded-key") == "Basic encoded-key"


def _insert_scan(
    database_url: str,
    *,
    addr: str,
    scanned_at: str,
    rssi: int | None = -55,
    raw: str = APPLE_ADV_RAW,
    local_name: str | None = "Name",
    lat: float | None = 37.1,
    lon: float | None = -122.2,
    accuracy: float | None = 3.5,
) -> int:
    blob_uri = f"gs://test-bucket/wigle/{uuid4().hex}.jsonl"
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

    return _minute_id(database_url, addr, scanned_at)


def _minute_id(database_url: str, addr: str, scanned_at: str) -> int:
    return int(
        pl.read_database_uri(
            """
            select m.id
            from sighting_minutes m
            where m.addr = cast($1 as macaddr)
              and m.minute = date_trunc('minute', cast($2 as timestamptz), 'UTC')
            """,
            database_url,
            engine="adbc",
            execute_options={"parameters": [addr, scanned_at]},
        ).item()
    )


def _claim_pending_minutes(database_url: str) -> None:
    """Mark everything already exportable as uploaded by one batch."""
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            with candidate as materialized (
              select m.id, m.minute
              from sighting_minutes m
              where m.location is not null
                and m.best_rssi is not null
                and not exists (
                  select 1
                  from wigle_upload_batch_minutes bm
                  join wigle_upload_batches b on b.id = bm.batch_id
                  where bm.minute_id = m.id
                    and bm.minute = m.minute
                    and b.status in (
                      'created', 'exported', 'uploading', 'uploaded', 'completed'
                    )
                )
            ),
            new_batch as (
              insert into wigle_upload_batches (status, filename)
              select 'uploaded', 'preexisting-test-rows.csv'
              where exists (select 1 from candidate)
              returning id
            ),
            inserted as (
              insert into wigle_upload_batch_minutes (batch_id, minute_id, minute)
              select new_batch.id, candidate.id, candidate.minute
              from new_batch
              cross join candidate
              returning batch_id, minute_id
            ),
            stats as (
              select
                batch_id,
                count(*)::bigint as row_count,
                min(minute_id)::bigint as min_item_id,
                max(minute_id)::bigint as max_item_id
              from inserted
              group by batch_id
            )
            update wigle_upload_batches b
            set
              row_count = stats.row_count,
              min_item_id = stats.min_item_id,
              max_item_id = stats.max_item_id
            from stats
            where b.id = stats.batch_id
            """
        )


def test_write_wigle_csv_exports_bluetooth_rows(database_url: str) -> None:
    _claim_pending_minutes(database_url)
    _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:01",
        scanned_at="2026-01-01T00:00:00Z",
    )

    output = io.StringIO()
    row_count = write_wigle_csv(database_url, output, limit=10)

    rows = list(csv.reader(io.StringIO(output.getvalue())))
    assert row_count == 1
    assert rows[0] == [
        "WigleWifi-1.6",
        "appRelease=unknown",
        "model=unknown",
        "release=unknown",
        "device=unknown",
        "display=unknown",
        "board=unknown",
        "brand=unknown",
        "star=Sol",
        "body=3",
        "subBody=0",
    ]
    assert rows[1] == list(WIGLE_CSV_HEADER)
    assert rows[2] == [
        "aa:bb:cc:00:10:01",
        "Name",
        "Misc [LE]",
        "2026-01-01 00:00:00",
        "0",
        "",
        "-55",
        "37.1",
        "-122.2",
        "",
        "3.5",
        "",
        "76",
        "BLE",
    ]


def test_write_wigle_csv_without_limit_exports_every_pending_row(
    database_url: str,
) -> None:
    """No --limit means no limit; the parameter cannot be null (ADBC)."""
    _claim_pending_minutes(database_url)
    _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:0A",
        scanned_at="2026-01-01T00:00:00Z",
    )
    _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:0B",
        scanned_at="2026-01-01T00:00:01Z",
    )

    output = io.StringIO()
    row_count = write_wigle_csv(database_url, output)

    assert row_count == 2


def test_wigle_upload_batches_claim_only_unuploaded_eligible_minutes(
    database_url: str,
) -> None:
    _claim_pending_minutes(database_url)
    first_minute_id = _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:02",
        scanned_at="2026-01-01T00:00:01Z",
    )
    second_minute_id = _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:03",
        scanned_at="2026-01-01T00:00:02Z",
    )
    _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:04",
        scanned_at="2026-01-01T00:00:03Z",
        lat=None,
        lon=None,
        accuracy=None,
    )

    assert count_pending_wigle_rows(database_url) == 2

    first_batch = create_wigle_upload_batch(
        database_url,
        batch_size=1,
        filename="first.csv",
    )
    assert count_pending_wigle_rows(database_url) == 1

    second_batch = create_wigle_upload_batch(
        database_url,
        batch_size=10,
        filename="second.csv",
    )
    assert count_pending_wigle_rows(database_url) == 0

    assert first_batch is not None
    assert second_batch is not None
    assert first_batch.row_count == 1
    assert first_batch.min_item_id == first_minute_id
    assert first_batch.max_item_id == first_minute_id
    assert second_batch.row_count == 1
    assert second_batch.min_item_id == second_minute_id
    assert second_batch.max_item_id == second_minute_id


def test_export_wigle_batch_csv_marks_batch_exported(
    database_url: str,
    tmp_path: Path,
) -> None:
    _claim_pending_minutes(database_url)
    minute_id = _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:05",
        scanned_at="2026-01-01T00:00:04Z",
    )
    batch = create_wigle_upload_batch(
        database_url,
        batch_size=10,
        filename="export.csv",
    )

    assert batch is not None
    assert batch.min_item_id == minute_id

    result = export_wigle_batch_csv(
        database_url,
        batch_id=batch.id,
        output_path=tmp_path / "export.csv",
    )

    assert result.row_count == 1
    row = pl.read_database_uri(
        """
        select status, row_count, filename, csv_sha256
        from wigle_upload_batches
        where id = $1
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [batch.id]},
    ).to_dicts()[0]
    assert row == {
        "status": "exported",
        "row_count": 1,
        "filename": "export.csv",
        "csv_sha256": result.csv_sha256,
    }


def test_wigle_upload_command_drains_pending_batches(
    database_url: str,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            update wigle_upload_batches
            set status = 'uploaded'
            where status in ('created', 'exported')
            """
        )

    _claim_pending_minutes(database_url)
    for index in range(3):
        _insert_scan(
            database_url,
            addr=f"AA:BB:CC:00:20:{index:02X}",
            scanned_at=f"2026-01-01T00:01:0{index}Z",
        )
    assert count_pending_wigle_rows(database_url) == 3

    upload_count = 0

    def fake_upload_wigle_file(
        *,
        file_path: Path,
        auth_header: str,
        donate: bool = False,
        timeout_seconds: int = 120,
    ) -> dict[str, Any]:
        nonlocal upload_count
        upload_count += 1
        assert file_path.exists()
        assert auth_header == "Basic test-key"
        assert donate is False
        assert timeout_seconds == 120
        return {
            "success": True,
            "results": {"transids": [{"transId": f"tx-{upload_count}"}]},
        }

    monkeypatch.setattr(cli, "upload_wigle_file", fake_upload_wigle_file)

    result = CliRunner().invoke(
        cli.app,
        [
            "wigle",
            "upload",
            "--database-url",
            database_url,
            "--api-key",
            "test-key",
            "--batch-size",
            "1",
            "--work-dir",
            str(tmp_path),
        ],
    )

    assert result.exit_code == 0
    assert upload_count == 3
    assert "uploaded batches=3 rows=3" in result.output
    assert count_pending_wigle_rows(database_url) == 0

    batches = pl.read_database_uri(
        """
        select status, row_count, wigle_transids::text as wigle_transids
        from wigle_upload_batches
        order by id desc
        limit 3
        """,
        database_url,
        engine="adbc",
    ).to_dicts()

    assert batches == [
        {"status": "uploaded", "row_count": 1, "wigle_transids": '["tx-3"]'},
        {"status": "uploaded", "row_count": 1, "wigle_transids": '["tx-2"]'},
        {"status": "uploaded", "row_count": 1, "wigle_transids": '["tx-1"]'},
    ]


# --- one row per address per minute -------------------------------------------------

_FLAGS = bytes.fromhex("020106")
_MFG_APPLE = bytes.fromhex("05ff4c000102")


def _name(name: str) -> bytes:
    return bytes([len(name) + 1, 0x09]) + name.encode()


def _ingest_v3(database_url: str, suffix: str, rows: list[dict[str, Any]]) -> None:
    """Ingest one synthetic scans/v3 file (the rows override the defaults)."""

    defaults: dict[str, Any] = {
        "rssi": -60,
        "local_name": None,
        "tx_power": None,
        "is_connectable": True,
        "lat": None,
        "lon": None,
        "accuracy": None,
        **{
            name: None
            for name in (
                "is_legacy",
                "primary_phy",
                "secondary_phy",
                "adv_sid",
                "adv_tx_power",
                "periodic_interval",
                "data_status",
                "addr_type",
            )
        },
    }
    payload_id = f"0195c920-7c00-7abc-8def-01234567{suffix}"
    uri = f"gs://test-bucket/scans/v3/2025/03/24/{payload_id}.parquet.encrypted"
    frame = pl.DataFrame(
        [{**defaults, **row} for row in rows], schema=PAYLOAD_V3_POLARS_SCHEMA
    )
    buffer = io.BytesIO()
    frame.write_parquet(
        buffer,
        metadata={
            "cluetooth.payload_schema": "v3",
            "cluetooth.payload_id": payload_id,
        },
    )
    key = PrivateKey(bytes(range(32)))
    ciphertext = SealedBox(key.public_key).encrypt(buffer.getvalue())
    assert (
        _ingest_blob(database_url, bytes(key), BlobBytes(uri, ciphertext)) is not None
    )


def _seen(second: int) -> datetime:
    return datetime(2031, 6, 1, 12, 0, second, tzinfo=UTC)


def _minute_rows(database_url: str, addr: str) -> int:
    return int(
        pl.read_database_uri(
            "select count(*) from sighting_minutes where addr = cast($1 as macaddr)",
            database_url,
            engine="adbc",
            execute_options={"parameters": [addr]},
        ).item()
    )


def test_one_row_per_address_minute_across_payloads_and_pdu_kinds(
    database_url: str, tmp_path: Path
) -> None:
    _claim_pending_minutes(database_url)
    addr = "AA:BB:CC:00:30:01"
    legacy = {"is_legacy": True, "primary_phy": 1, "secondary_phy": 0}
    ext_2m = {"is_legacy": False, "primary_phy": 1, "secondary_phy": 2, "adv_sid": 1}
    coded = {"is_legacy": False, "primary_phy": 3, "secondary_phy": 3, "adv_sid": 1}
    old = _FLAGS + _name("Old")
    newer = _FLAGS + _name("Newer") + _MFG_APPLE
    unnamed = _FLAGS + bytes.fromhex("0302f0fe")
    latest = _FLAGS + _name("Latest")
    _ingest_v3(
        database_url,
        "0301",
        [
            # Four payloads and four PDU kinds of one address in one minute.
            # Worst fix (rssi -80, accuracy 20 m).
            {
                "addr": addr,
                "raw": old,
                "scanned_at": _seen(10),
                "rssi": -80,
                "lat": 10.0,
                "lon": 20.0,
                "accuracy": 20.0,
                **legacy,
            },
            # Best fix (rssi -50, accuracy 5 m), and the only manufacturer id.
            {
                "addr": addr,
                "raw": newer,
                "scanned_at": _seen(20),
                "rssi": -50,
                "lat": 32.8801,
                "lon": -117.234,
                "accuracy": 5.0,
                **ext_2m,
            },
            {
                "addr": addr,
                "raw": unnamed,
                "scanned_at": _seen(40),
                "rssi": -60,
                "lat": 11.0,
                "lon": 21.0,
                "accuracy": 5.0,
                **coded,
            },
            {
                "addr": addr,
                "raw": newer,
                "scanned_at": _seen(45),
                "rssi": -90,
                "lat": 12.0,
                "lon": 22.0,
                "accuracy": 50.0,
                **ext_2m,
                "adv_sid": 2,
            },
            # Newest name, but no position: it still names the row.
            {
                "addr": addr,
                "raw": latest,
                "scanned_at": _seen(55),
                "rssi": -70,
                **legacy,
                "primary_phy": 3,
            },
        ],
    )
    assert _minute_rows(database_url, addr) == 5

    assert count_pending_wigle_rows(database_url) == 1
    output = io.StringIO()
    assert write_wigle_csv(database_url, output) == 1
    rows = list(csv.reader(io.StringIO(output.getvalue())))
    assert len(rows) == 3  # pre-header, header, one row
    assert dict(zip(rows[1], rows[2], strict=True)) == {
        "MAC": addr.lower(),
        "SSID": "Latest",
        "AuthMode": "Misc [LE]",
        "FirstSeen": "2031-06-01 12:00:20",
        "Channel": "0",
        "Frequency": "",
        "RSSI": "-50",
        "CurrentLatitude": "32.8801",
        "CurrentLongitude": "-117.234",
        "AltitudeMeters": "",
        "AccuracyMeters": "5",
        "RCOIs": "",
        "MfgrId": "76",
        "Type": "BLE",
    }

    # The batch claims the address-minute: one CSV row, every minute row a member.
    batch = create_wigle_upload_batch(database_url, batch_size=10, filename="g.csv")
    assert batch is not None
    assert batch.row_count == 1
    members = pl.read_database_uri(
        "select count(*) from wigle_upload_batch_minutes where batch_id = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [batch.id]},
    ).item()
    assert members == 5
    result = export_wigle_batch_csv(
        database_url, batch_id=batch.id, output_path=tmp_path / "g.csv"
    )
    assert result.row_count == 1
    exported = list(csv.reader((tmp_path / "g.csv").read_text().splitlines()))
    assert exported[2][:2] == [addr.lower(), "Latest"]
    assert exported[2][6:11] == ["-50", "32.8801", "-117.234", "", "5"]
    assert count_pending_wigle_rows(database_url) == 0

    # Nothing is uploaded twice: a row of the same address-minute that arrives
    # later (another payload, another PDU kind, even a better fix) is already
    # covered, while the next minute of the address is pending.
    _ingest_v3(
        database_url,
        "0302",
        [
            {
                "addr": addr,
                "raw": _FLAGS + _name("Late"),
                "scanned_at": _seen(58),
                "rssi": -30,
                "lat": 1.0,
                "lon": 2.0,
                "accuracy": 1.0,
                **coded,
                "adv_sid": 9,
            },
        ],
    )
    assert _minute_rows(database_url, addr) == 6
    assert count_pending_wigle_rows(database_url) == 0
    assert (
        create_wigle_upload_batch(database_url, batch_size=10, filename="h.csv") is None
    )

    _ingest_v3(
        database_url,
        "0303",
        [
            {
                "addr": addr,
                "raw": newer,
                "scanned_at": datetime(2031, 6, 1, 12, 1, 5, tzinfo=UTC),
                "rssi": -55,
                "lat": 3.0,
                "lon": 4.0,
                "accuracy": 3.0,
                **ext_2m,
            },
        ],
    )
    assert count_pending_wigle_rows(database_url) == 1
