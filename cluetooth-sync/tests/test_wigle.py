from __future__ import annotations

import csv
import io
import json
from pathlib import Path
from uuid import uuid4

import polars as pl
import pytest
from typer.testing import CliRunner

import cluetooth_sync.cli as cli
import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline import insert_prepared_scans, prepare_scan_jsonl_bytes
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

    return int(
        pl.read_database_uri(
            """
            select max(id)::bigint as id
            from scans
            where blob = $1
            """,
            database_url,
            engine="adbc",
            execute_options={"parameters": [blob_uri]},
        )
        .select("id")
        .item()
    )


def _mark_existing_eligible_scans_uploaded(database_url: str) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            with candidate as (
              select s.id
              from scans s
              where s.lat is not null
                and s.lon is not null
                and s.rssi is not null
                and not exists (
                  select 1
                  from wigle_upload_batch_scans bs
                  where bs.scan_id = s.id
                )
            ),
            new_batch as (
              insert into wigle_upload_batches (status, filename)
              select 'uploaded', 'preexisting-test-rows.csv'
              where exists (select 1 from candidate)
              returning id
            ),
            inserted as (
              insert into wigle_upload_batch_scans (batch_id, scan_id)
              select new_batch.id, candidate.id
              from new_batch
              cross join candidate
              returning batch_id, scan_id
            ),
            stats as (
              select
                batch_id,
                count(*)::bigint as row_count,
                min(scan_id)::bigint as min_scan_id,
                max(scan_id)::bigint as max_scan_id
              from inserted
              group by batch_id
            )
            update wigle_upload_batches b
            set
              row_count = stats.row_count,
              min_scan_id = stats.min_scan_id,
              max_scan_id = stats.max_scan_id
            from stats
            where b.id = stats.batch_id
            """
        )


def test_write_wigle_csv_exports_bluetooth_rows(database_url: str) -> None:
    _mark_existing_eligible_scans_uploaded(database_url)
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


def test_wigle_upload_batches_claim_only_unuploaded_eligible_scans(
    database_url: str,
) -> None:
    _mark_existing_eligible_scans_uploaded(database_url)
    first_scan_id = _insert_scan(
        database_url,
        addr="AA:BB:CC:00:10:02",
        scanned_at="2026-01-01T00:00:01Z",
    )
    second_scan_id = _insert_scan(
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
    assert first_batch.min_scan_id == first_scan_id
    assert first_batch.max_scan_id == first_scan_id
    assert second_batch.row_count == 1
    assert second_batch.min_scan_id == second_scan_id
    assert second_batch.max_scan_id == second_scan_id


def test_export_wigle_batch_csv_marks_batch_exported(
    database_url: str,
    tmp_path: Path,
) -> None:
    _mark_existing_eligible_scans_uploaded(database_url)
    scan_id = _insert_scan(
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
    assert batch.min_scan_id == scan_id

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

    _mark_existing_eligible_scans_uploaded(database_url)
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
    ) -> dict[str, object]:
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
