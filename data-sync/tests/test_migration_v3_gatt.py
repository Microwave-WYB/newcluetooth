"""The v3/GATT migration on disposable databases: up, down, up, and the guard."""

from __future__ import annotations

import shutil
import subprocess
import time
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
        "values ('\\x01', '{}', '2031-03-01 00:00:10+00', '2031-03-01 00:00:50+00', 4) "
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
    # A v3 file from an old Android reports no PDU field: its row has an all-null
    # key, but only a v3 blob behind it.
    _sql(
        scratch_url,
        "insert into sighting_minutes (minute, addr, payload_id, blob_counts, "
        "sightings, first_seen, last_seen) values ('2031-03-01 00:00:00+00', "
        f"'aa:00:00:00:00:09', {payload_id}, '{{\"{v3_blob}\": 1}}', 1, "
        "'2031-03-01 00:00:20+00', '2031-03-01 00:00:20+00')",
    )
    assert _sql(scratch_url, "select count(*) from sighting_minutes") == [(4,)]

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
    # Only the row the older code wrote survives, however its key looked (the
    # all-null v3 row went too, by provenance); the old key is back.
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


def test_rollback_refuses_only_rows_that_mix_v3_and_other_blobs(
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
    [(v2_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success) "
        "values ('gs://b/scans/v2/2031/03/01/old.parquet.encrypted', true) returning id",
    )
    # A v3 file whose rows reported no PDU field (old Android) next to a v2 file of
    # the same phone and minute: one null-key row with both blobs behind it.
    _sql(
        scratch_url,
        "insert into sighting_minutes (minute, addr, payload_id, blob_counts, sightings) "
        "values ('2031-03-01 00:00:00+00', 'aa:00:00:00:00:02', "
        f'{payload_id}, \'{{"{v3_blob}": 1, "{v2_blob}": 1}}\', 2)',
    )

    result = _dbmate(scratch_url, directory, "rollback")
    assert result.returncode != 0
    assert "mixes scans/v3 blobs with other blobs" in result.stdout + result.stderr
    # Nothing changed: still migrated, ledger untouched.
    assert "adv_sid" in _columns(scratch_url, "sighting_minutes")
    assert _sql(scratch_url, "select success from blobs order by id") == [
        (True,),
        (True,),
    ]
    assert _sql(scratch_url, "select count(*) from sighting_minutes") == [(1,)]


def test_rollback_at_realistic_size_recomputes_payload_stats_in_one_pass(
    scratch_url: str, tmp_path: Path
) -> None:
    """1M minute rows over 2k payloads; a per-payload probe took 27 s here."""
    directory = _migrations_up_to(tmp_path, include_this=True)
    assert _dbmate(scratch_url, directory, "up").returncode == 0
    payloads, rows = 2_000, 1_000_000
    _sql(
        scratch_url,
        "insert into payloads (sha256, structure_ids) "
        f"select sha256(int4send(g)), '{{}}' from generate_series(1, {payloads}) g",
    )
    [(v2_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success) "
        "values ('gs://b/scans/v2/2031/03/01/big.parquet.encrypted', true) returning id",
    )
    [(v3_blob,)] = _sql(
        scratch_url,
        "insert into blobs (uri, success) "
        "values ('gs://b/scans/v3/2031/03/01/big.parquet.encrypted', true) returning id",
    )
    # Every 10th row is a v3 row: half with PDU fields, half with an all-null key.
    _sql(
        scratch_url,
        f"""
        insert into sighting_minutes (
          minute, addr, payload_id, blob_counts, sightings, first_seen, last_seen,
          is_legacy, adv_sid
        )
        select
          timestamptz '2031-03-01 00:00:00+00' + (g % 1000) * interval '1 minute',
          lpad(to_hex(g), 12, '0')::macaddr,
          (select min(id) from payloads) + g % {payloads},
          case when g % 10 = 0 then jsonb_build_object('{v3_blob}', 1)
               else jsonb_build_object('{v2_blob}', 1) end,
          1,
          timestamptz '2031-03-01 00:00:00+00' + (g % 1000) * interval '1 minute',
          timestamptz '2031-03-01 00:00:00+00' + (g % 1000) * interval '1 minute',
          case when g % 20 = 0 then false end,
          case when g % 20 = 0 then g % 16 end
        from generate_series(1, {rows}) g
        """,
    )
    _sql(
        scratch_url,
        """
        update payloads p
        set sightings = s.n, first_seen = s.first_seen, last_seen = s.last_seen
        from (
          select payload_id, sum(sightings) as n, min(first_seen) as first_seen,
                 max(last_seen) as last_seen
          from sighting_minutes group by payload_id
        ) s
        where p.id = s.payload_id
        """,
    )

    started = time.monotonic()
    result = _dbmate(scratch_url, directory, "rollback")
    elapsed = time.monotonic() - started
    assert result.returncode == 0, result.stderr

    assert _sql(scratch_url, "select count(*) from sighting_minutes") == [
        (rows - rows // 10,)
    ]
    # Every payload's stats equal what its remaining rows say.
    assert _sql(
        scratch_url,
        """
        select count(*)
        from payloads p
        left join (
          select payload_id, sum(sightings) as n, min(first_seen) as first_seen,
                 max(last_seen) as last_seen
          from sighting_minutes group by payload_id
        ) r on r.payload_id = p.id
        where p.sightings is distinct from coalesce(r.n, 0)
           or p.first_seen is distinct from r.first_seen
           or p.last_seen is distinct from r.last_seen
        """,
    ) == [(0,)]
    # The delete, the one-pass recompute and the rest take seconds; probing the
    # table once per payload would take far longer than this bound.
    print(f"rollback of {rows} rows / {payloads} payloads: {elapsed:.1f}s")
    assert elapsed < 10, f"rollback took {elapsed:.1f}s"
