"""The v3/GATT migration on disposable databases: up, down, up, and the guard."""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Iterator
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import adbc_driver_postgresql.dbapi as pg_dbapi
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = REPO_ROOT / "db/db/migrations"
THIS = "20261027120000_payload_v3_gatt.sql"


def _dbmate(url: str, migrations_dir: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            "dbmate",
            "--url",
            url,
            "--migrations-dir",
            str(migrations_dir),
            "--no-dump-schema",
            *args,
        ],
        cwd=REPO_ROOT / "db",
        capture_output=True,
        text=True,
    )


def _sql(url: str, statement: str, *parameters: Any) -> list[tuple[Any, ...]]:
    connection = pg_dbapi.connect(url, autocommit=True)
    cursor = connection.cursor()
    try:
        cursor.execute(statement, list(parameters) or None)
        # cursor.description is not supported by the Polars ADBC backend.
        lowered = statement.lstrip().lower()
        if lowered.startswith("select") or " returning " in lowered:
            return cursor.fetch_polars().rows()
        return []
    finally:
        cursor.close()
        connection.close()


@pytest.fixture
def scratch_url(database_url: str) -> Iterator[str]:
    """An empty database of its own: these tests control which migrations ran."""
    name = f"cluetooth_mig_{uuid4().hex[:12]}"
    _sql(database_url, f'create database "{name}"')
    parts = urlsplit(database_url)
    try:
        yield urlunsplit(parts._replace(path=f"/{name}"))
    finally:
        _sql(database_url, f'drop database if exists "{name}" with (force)')


def _migrations_up_to(tmp_path: Path, *, include_this: bool) -> Path:
    directory = tmp_path / ("with" if include_this else "without")
    directory.mkdir()
    for path in sorted(MIGRATIONS.glob("*.sql")):
        if path.name < THIS or (include_this and path.name == THIS):
            shutil.copy(path, directory / path.name)
    return directory


def _columns(url: str, table: str) -> set[str]:
    return {
        row[0]
        for row in _sql(
            url,
            "select column_name from information_schema.columns "
            f"where table_schema = 'public' and table_name = '{table}'",
        )
    }


def test_migration_rolls_back_and_reapplies(scratch_url: str, tmp_path: Path) -> None:
    directory = _migrations_up_to(tmp_path, include_this=True)
    assert _dbmate(scratch_url, directory, "up").returncode == 0

    # v3 side: the new key, and rows of both kinds.
    assert {"adv_sid", "adv_tx_power", "periodic_interval", "data_status"} <= _columns(
        scratch_url, "sighting_minutes"
    )
    [(payload_id,)] = _sql(
        scratch_url,
        "insert into payloads (sha256, structure_ids, first_seen, last_seen, sightings) "
        "values ('\\x01', '{}', '2031-03-01 00:00:10+00', '2031-03-01 00:00:50+00', 3) "
        "returning id",
    )
    [(v2_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success, attempts) "
        "values ('gs://b/scans/v2/2031/03/01/old.parquet.encrypted', true, 0) returning id",
    )
    [(v3_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success, attempts, error, synced_at) "
        "values ('gs://b/scans/v3/2031/03/01/new.parquet.encrypted', true, 2, 'old', now()) "
        "returning id",
    )

    def insert_minute(is_legacy: str, adv_sid: str, blob: int, seen: str) -> str:
        return (
            "insert into sighting_minutes (minute, addr, payload_id, blob_counts, "
            "sightings, first_seen, last_seen, is_legacy, adv_sid) "
            "values ('2031-03-01 00:00:00+00', "
            f"'aa:00:00:00:00:01', {payload_id}, '{{\"{blob}\": 1}}', 1, "
            f"'2031-03-01 00:00:{seen}+00', '2031-03-01 00:00:{seen}+00', "
            f"{is_legacy}, {adv_sid})"
        )

    _sql(scratch_url, insert_minute("null", "null", v2_blob, "10"))
    _sql(scratch_url, insert_minute("true", "1", v3_blob, "30"))
    _sql(scratch_url, insert_minute("true", "2", v3_blob, "50"))
    assert _sql(scratch_url, "select count(*) from sighting_minutes") == [(3,)]
    # adv_shapes counts distinct minutes, not minute rows.
    assert _sql(
        scratch_url,
        "select minutes, sightings from adv_shapes where addr = 'aa:00:00:00:00:01'",
    ) == [(1, 3)]
    # The same key twice is refused (nulls compare equal).
    with pytest.raises(
        Exception, match="duplicate key value violates unique constraint"
    ):
        _sql(scratch_url, insert_minute("null", "null", v2_blob, "10"))

    # GATT side.
    [(blob_id,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success) "
        "values ('gs://b/gatt/v1/2031/03/01/x.json.encrypted', true) returning id",
    )
    [(discovery_id,)] = _sql(
        scratch_url,
        "insert into gatt_discoveries (blob_id, discovery_uuid, addr, started_at, "
        "outcome, stage_reached, lat, lon, raw) values "
        f"({blob_id}, gen_random_uuid(), 'aa:00:00:00:00:01', now(), 'complete', "
        "'disconnect', 1, 2, '{}') returning id",
    )
    assert _sql(
        scratch_url,
        "select st_astext(location) from gatt_discoveries",
    ) == [("POINT(2 1)",)]
    _sql(
        scratch_url,
        "insert into gatt_attributes (discovery_id, attribute_id, kind, uuid) "
        f"values ({discovery_id}, 1, 'service', '180a')",
    )
    with pytest.raises(Exception, match="gatt_attributes_read"):
        _sql(
            scratch_url,
            "insert into gatt_attributes (discovery_id, attribute_id, parent_attribute_id, "
            f"kind, uuid) values ({discovery_id}, 2, 1, 'characteristic', '2a29')",
        )

    down = _dbmate(scratch_url, directory, "rollback")
    assert down.returncode == 0, down.stderr

    assert _columns(scratch_url, "gatt_discoveries") >= {"discovered_at", "raw_profile"}
    assert _columns(scratch_url, "gatt_attributes") >= {"data", "attribute_id"}
    assert not {
        "adv_sid",
        "adv_tx_power",
        "periodic_interval",
        "data_status",
    } & _columns(scratch_url, "sighting_minutes")
    # Only the row without v3 key fields survives; the old key is back.
    assert _sql(scratch_url, "select count(*) from sighting_minutes") == [(1,)]
    # The ledger is put back: v3 and GATT objects are pending again, others are not.
    ledger = {
        uri.rsplit("/", 1)[-1]: row
        for uri, *row in _sql(
            scratch_url,
            "select uri, success, attempts, error, synced_at is null from blobs",
        )
    }
    assert ledger == {
        "old.parquet.encrypted": [True, 0, None, True],
        "new.parquet.encrypted": [None, 0, None, True],
        "x.json.encrypted": [None, 0, None, True],
    }
    # Payload stats no longer count the deleted rows, so a re-ingest is exact.
    assert _sql(
        scratch_url, "select sightings, first_seen::text, last_seen::text from payloads"
    ) == [(1, "2031-03-01 00:00:10+00", "2031-03-01 00:00:10+00")]
    assert _sql(
        scratch_url,
        "select minutes, sightings from adv_shapes where addr = 'aa:00:00:00:00:01'",
    ) == [(1, 1)]
    with pytest.raises(
        Exception, match="duplicate key value violates unique constraint"
    ):
        _sql(
            scratch_url,
            "insert into sighting_minutes (minute, addr, payload_id, blob_counts, "
            "sightings) values ('2031-03-01 00:00:00+00', 'aa:00:00:00:00:01', "
            f"{payload_id}, '{{}}', 1)",
        )
    # The legacy coordinate checks are back too.
    constraints = {
        row[0]
        for row in _sql(
            scratch_url,
            "select conname from pg_constraint where conrelid = 'gatt_discoveries'::regclass",
        )
    }
    assert "gatt_discoveries_lat_range" in constraints

    up = _dbmate(scratch_url, directory, "up")
    assert up.returncode == 0, up.stderr
    assert "adv_sid" in _columns(scratch_url, "sighting_minutes")


def test_migration_refuses_to_replace_non_empty_legacy_gatt_tables(
    scratch_url: str, tmp_path: Path
) -> None:
    before = _migrations_up_to(tmp_path, include_this=False)
    assert _dbmate(scratch_url, before, "up").returncode == 0
    _sql(
        scratch_url,
        "insert into gatt_discoveries (addr, discovered_at, raw_profile) "
        "values ('aa:00:00:00:00:01', now(), '{}')",
    )

    result = _dbmate(scratch_url, _migrations_up_to(tmp_path, include_this=True), "up")
    assert result.returncode != 0
    assert "refusing to replace" in result.stdout + result.stderr
    # Nothing changed: the legacy row and table are intact, no half-applied state.
    assert _sql(scratch_url, "select count(*) from gatt_discoveries") == [(1,)]
    assert "discovered_at" in _columns(scratch_url, "gatt_discoveries")
    assert "adv_sid" not in _columns(scratch_url, "sighting_minutes")


def test_rollback_refuses_when_a_v3_blob_also_fed_rows_without_v3_fields(
    scratch_url: str, tmp_path: Path
) -> None:
    directory = _migrations_up_to(tmp_path, include_this=True)
    assert _dbmate(scratch_url, directory, "up").returncode == 0
    [(payload_id,)] = _sql(
        scratch_url,
        "insert into payloads (sha256, structure_ids) values ('\\x02', '{}') returning id",
    )
    [(v3_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success) "
        "values ('gs://b/scans/v3/2031/03/01/mixed.parquet.encrypted', true) returning id",
    )
    # A v3 file whose rows reported no PDU field: its minute row is a null-key row.
    _sql(
        scratch_url,
        "insert into sighting_minutes (minute, addr, payload_id, blob_counts, sightings) "
        "values ('2031-03-01 00:00:00+00', 'aa:00:00:00:00:02', "
        f"{payload_id}, '{{\"{v3_blob}\": 1}}', 1)",
    )

    result = _dbmate(scratch_url, directory, "rollback")
    assert result.returncode != 0
    assert "also contributed to minute rows without v3 key fields" in (
        result.stdout + result.stderr
    )
    # Nothing changed: still migrated, ledger untouched.
    assert "adv_sid" in _columns(scratch_url, "sighting_minutes")
    assert _sql(scratch_url, "select success from blobs") == [(True,)]
