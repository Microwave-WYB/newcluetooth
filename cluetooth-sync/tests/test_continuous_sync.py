from __future__ import annotations

import json
import threading
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import adbc_driver_postgresql.dbapi as pg_dbapi
import polars as pl
import pytest
from typer.testing import CliRunner

import cluetooth_sync.cli as cli
import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline import (
    CycleStats,
    SyncStatus,
    check_heartbeat,
    discover_pending_blobs,
    insert_prepared_scans,
    prepare_scan_jsonl_bytes,
    write_heartbeat,
)
from cluetooth_sync.pipeline.ingest import mark_blob_failed
from cluetooth_sync.pipeline.queries import read_query
from cluetooth_sync.pipeline.survey import survey_blobs


SCAN_FIXTURE_PATH = Path(__file__).parent / "fixtures" / "scans.jsonl"


class _ListingStorage:
    def __init__(self, uris: list[str]) -> None:
        self._uris = uris

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        uri_prefix = f"gs://{bucket_name}/{prefix}"
        return [uri for uri in self._uris if uri.startswith(uri_prefix)]

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        raise AssertionError("must not download")


def _execute(database_url: str, query: str, parameters: list[object]) -> None:
    connection = pg_dbapi.connect(database_url, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute(query, parameters)
    finally:
        cursor.close()
        connection.close()


def _blob_row(database_url: str, uri: str) -> dict[str, Any]:
    return pl.read_database_uri(
        """
        select success, attempts, error,
          extract(epoch from next_attempt_at - now())::float8 as retry_in
        from blobs where uri = $1
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).to_dicts()[0]


def test_failed_blob_backs_off_then_is_retried(database_url: str) -> None:
    prefix = "continuous/backoff/"
    uri = f"gs://test-bucket/{prefix}bad.jsonl.zst.encrypted"
    storage = _ListingStorage([uri])

    mark_blob_failed(database_url, uri, ValueError("bad hex"))
    first = _blob_row(database_url, uri)
    assert first["success"] is False
    assert first["attempts"] == 1
    assert 50 < first["retry_in"] <= 60

    mark_blob_failed(database_url, uri, ValueError("bad hex"))
    second = _blob_row(database_url, uri)
    assert second["attempts"] == 2
    assert 110 < second["retry_in"] <= 120

    # Deferred while inside its backoff window; still pending, never succeeded.
    assert (
        list(discover_pending_blobs(storage, database_url, "test-bucket", prefix)) == []
    )

    _execute(
        database_url,
        "update blobs set next_attempt_at = now() - interval '1 second' where uri = $1",
        [uri],
    )
    assert list(
        discover_pending_blobs(storage, database_url, "test-bucket", prefix)
    ) == [uri]

    for _ in range(20):
        mark_blob_failed(database_url, uri, ValueError("bad hex"))
    assert _blob_row(database_url, uri)["retry_in"] <= 3600


def test_rebuild_lock_waits_for_in_flight_ingest(database_url: str) -> None:
    connection = pg_dbapi.connect(database_url)
    cursor = connection.cursor()
    try:
        cursor.execute(
            "select true as locked from pg_advisory_xact_lock_shared(4381022501)"
        )
        cursor.fetch_polars()

        result: list[str] = []

        def take_rebuild_lock() -> None:
            with db.session(database_url) as session:
                session.cursor.execute(read_query("sightings/rebuild_lock.sql"))
                session.cursor.fetch_polars()
                result.append("locked")

        waiter = threading.Thread(target=take_rebuild_lock)
        waiter.start()
        waiter.join(timeout=1.0)
        assert waiter.is_alive(), "rebuild must wait for the uncommitted ingest"

        connection.commit()
        waiter.join(timeout=10)
        assert result == ["locked"]
    finally:
        cursor.close()
        connection.close()


def test_skip_if_succeeded_prevents_double_ingest(clean_database_url: str) -> None:
    database_url = clean_database_url
    uri = "gs://test-bucket/continuous/double-ingest.jsonl"
    scans = prepare_scan_jsonl_bytes(SCAN_FIXTURE_PATH.read_bytes(), uri)
    assert insert_prepared_scans(database_url, scans, skip_if_succeeded=True) == 2
    assert insert_prepared_scans(database_url, scans, skip_if_succeeded=True) == 0

    count = pl.read_database_uri(
        """
        select coalesce(sum(m.sightings), 0)::int as count
        from sighting_minutes m
        join blobs b on b.id = any (m.blob_ids)
        where b.uri = $1
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).item()
    assert count == 2


def test_survey_is_list_only(database_url: str) -> None:
    prefix = "survey-scans/"
    done = f"gs://test-bucket/{prefix}2026-08-01T00-00-00.000Z_dev_0.0.4.jsonl.zst.encrypted"
    uris = [
        done,
        f"gs://test-bucket/{prefix}2026-10-02T00-00-00.000Z_dev_0.0.4.jsonl.zst.encrypted",
    ]
    _execute(
        database_url,
        "insert into blobs (uri, success) values ($1, true) on conflict do nothing",
        [done],
    )

    summary = survey_blobs(_ListingStorage(uris), database_url, "test-bucket", [prefix])

    assert summary.select("month", "objects", "succeeded", "new").to_dicts() == [
        {"month": "2026-08", "objects": 1, "succeeded": 1, "new": 0},
        {"month": "2026-10", "objects": 1, "succeeded": 0, "new": 1},
    ]


def test_heartbeat_health(tmp_path: Path) -> None:
    path = tmp_path / "heartbeat.json"
    status = SyncStatus()
    status.begin_cycle()
    status.end_cycle(CycleStats(discovered=3, completed=3, succeeded=2, failed=1))
    write_heartbeat(status, path)

    healthy, message = check_heartbeat(
        path, max_age_seconds=60, max_consecutive_errors=3
    )
    assert healthy, message
    assert json.loads(path.read_text())["total_failed"] == 1

    for _ in range(3):
        status.cycle_failed(RuntimeError("db down"))
    write_heartbeat(status, path)
    assert not check_heartbeat(path, max_age_seconds=60, max_consecutive_errors=3)[0]

    stale = json.loads(path.read_text())
    stale["updated_at"] = (datetime.now(UTC) - timedelta(minutes=10)).isoformat()
    path.write_text(json.dumps(stale))
    assert not check_heartbeat(path, max_age_seconds=60, max_consecutive_errors=9)[0]
    assert not check_heartbeat(
        tmp_path / "missing.json", max_age_seconds=60, max_consecutive_errors=3
    )[0]


def test_continuous_loop_survives_cycle_errors(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    key_path = tmp_path / "key"
    key_path.write_bytes(b"\xff" * 32)
    heartbeat = tmp_path / "heartbeat.json"
    calls: list[int] = []

    async def fake_pipeline(**kwargs: object) -> CycleStats:
        calls.append(1)
        if len(calls) <= 2:
            raise ConnectionError("database unavailable")
        if len(calls) == 3:
            return CycleStats(discovered=1, completed=1, succeeded=1, rows=5)
        raise KeyboardInterrupt

    monkeypatch.setattr(cli, "run_pipeline", fake_pipeline)
    monkeypatch.setattr(cli, "_build_storage_client", lambda *args: object())
    monkeypatch.setattr(cli.time, "sleep", lambda seconds: None)

    with pytest.raises(KeyboardInterrupt):
        cli.sync(
            database_url="db",
            bucket_name="bucket",
            private_key_path=key_path,
            poll_interval_seconds=1,
            heartbeat_path=heartbeat,
        )

    assert len(calls) == 4
    data = json.loads(heartbeat.read_text())
    assert data["consecutive_cycle_errors"] == 0
    assert data["total_succeeded"] == 1
    assert data["total_rows"] == 5
    assert "database unavailable" in data["last_cycle_error"]


def test_health_command_exit_codes(tmp_path: Path) -> None:
    path = tmp_path / "heartbeat.json"
    write_heartbeat(SyncStatus(), path)
    runner = CliRunner()
    ok = runner.invoke(cli.app, ["health", "--heartbeat-path", str(path)])
    assert ok.exit_code == 0, ok.output
    missing = runner.invoke(
        cli.app, ["health", "--heartbeat-path", str(tmp_path / "nope.json")]
    )
    assert missing.exit_code == 1
