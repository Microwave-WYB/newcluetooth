from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

import polars as pl
import pytest

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline import insert_prepared_scans, prepare_scan_jsonl_bytes
from cluetooth_sync.pipeline.queries import quote_pg_identifier
from cluetooth_sync.pipeline.sightings import create_raws_table, store_payloads


def _row(
    addr: str,
    scanned_at: str,
    raw: str,
    *,
    rssi: int | None = None,
    tx_power: int | None = None,
    is_connectable: bool | None = None,
    lat: float | None = None,
    lon: float | None = None,
    accuracy: float | None = None,
) -> str:
    return (
        json.dumps(
            {
                "addr": addr,
                "rssi": rssi,
                "scanned_at": scanned_at,
                "raw": raw,
                "local_name": None,
                "tx_power": tx_power,
                "is_connectable": is_connectable,
                "lat": lat,
                "lon": lon,
                "accuracy": accuracy,
            }
        )
        + "\n"
    )


def _ingest(database_url: str, blob_uri: str, rows: list[str]) -> int:
    scans = prepare_scan_jsonl_bytes("".join(rows).encode(), blob_uri)
    return insert_prepared_scans(database_url, scans)


def _store_payloads(database_url: str, raw: str) -> None:
    """Run the real payload write path for one raw advertisement."""
    with db.session(database_url) as session:
        raws = create_raws_table(session)
        session.cursor.execute(
            f"insert into {quote_pg_identifier(raws)} (raw) values (decode($1, 'hex'))",
            [raw],
        )
        store_payloads(session, raws)


_AGGREGATES = """
    m.addr::text as addr,
    m.sightings,
    m.first_seen,
    m.last_seen,
    m.rssi_min,
    m.rssi_max,
    m.rssi_sum,
    m.rssi_count,
    m.rssi_mean,
    m.best_rssi,
    m.best_accuracy,
    m.best_seen,
    st_y(m.location::geometry) as best_lat,
    st_x(m.location::geometry) as best_lon,
    m.min_lat,
    m.max_lat,
    m.min_lon,
    m.max_lon,
    m.tx_power,
    m.is_connectable
"""


def _minutes(database_url: str, day: str) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        f"""
        select {_AGGREGATES}
        from sighting_minutes m
        where m.minute >= timestamp '{day} 00:00:00+00'
          and m.minute < timestamp '{day} 01:00:00+00'
        order by m.addr::text
        """,
        database_url,
        engine="adbc",
    ).to_dicts()


def test_minute_split_across_blobs_merges_exactly(database_url: str) -> None:
    """Parts of one minute merge to the same row as a single pass, in any order."""
    raw = "02010605094e616d6505ff4c000102"
    weaker = _row(
        "AA:00:00:00:00:01",
        "2026-02-01T00:00:00.000Z",
        raw,
        rssi=-50,
        tx_power=-4,
        is_connectable=True,
        lat=10.0,
        lon=20.0,
        accuracy=5.0,
    )
    stronger = _row(
        "AA:00:00:00:00:01",
        "2026-02-01T00:00:30.000Z",
        raw,
        rssi=-70,
        tx_power=-8,
        is_connectable=False,
        lat=11.0,
        lon=21.0,
        accuracy=1.0,
    )

    # The same two samples split across two blobs, in both orders.
    assert _ingest(database_url, "gs://t/split/first.jsonl", [weaker]) == 1
    assert _ingest(database_url, "gs://t/split/second.jsonl", [stronger]) == 1
    reversed_addr = "AA:00:00:00:00:02"
    assert (
        _ingest(
            database_url,
            "gs://t/split/reversed-first.jsonl",
            [stronger.replace("AA:00:00:00:00:01", reversed_addr)],
        )
        == 1
    )
    assert (
        _ingest(
            database_url,
            "gs://t/split/reversed-second.jsonl",
            [weaker.replace("AA:00:00:00:00:01", reversed_addr)],
        )
        == 1
    )
    # Control: both samples in one object.
    single_addr = "AA:00:00:00:00:03"
    assert (
        _ingest(
            database_url,
            "gs://t/split/single.jsonl",
            [
                weaker.replace("AA:00:00:00:00:01", single_addr),
                stronger.replace("AA:00:00:00:00:01", single_addr),
            ],
        )
        == 2
    )

    rows = _minutes(database_url, "2026-02-01")
    assert [row["addr"] for row in rows] == [
        "aa:00:00:00:00:01",
        "aa:00:00:00:00:02",
        "aa:00:00:00:00:03",
    ]
    split, reversed_split, single = rows
    without_addr = {key: value for key, value in single.items() if key != "addr"}
    assert {key: value for key, value in split.items() if key != "addr"} == without_addr
    assert {
        key: value for key, value in reversed_split.items() if key != "addr"
    } == without_addr

    assert split["sightings"] == 2
    assert split["first_seen"] == datetime(2026, 2, 1, 0, 0, tzinfo=UTC)
    assert split["last_seen"] == datetime(2026, 2, 1, 0, 0, 30, tzinfo=UTC)
    assert split["rssi_min"] == -70
    assert split["rssi_max"] == -50
    assert split["rssi_sum"] == -120
    assert split["rssi_count"] == 2
    assert split["rssi_mean"] == pytest.approx(-60.0)
    assert split["min_lat"] == pytest.approx(10.0)
    assert split["max_lat"] == pytest.approx(11.0)
    assert split["min_lon"] == pytest.approx(20.0)
    assert split["max_lon"] == pytest.approx(21.0)
    assert split["tx_power"] == -4
    assert split["is_connectable"] is True
    # The stronger sample (-70 dBm at 1 m) wins the location.
    assert split["best_rssi"] == -70
    assert split["best_accuracy"] == pytest.approx(1.0)
    assert split["best_seen"] == datetime(2026, 2, 1, 0, 0, 30, tzinfo=UTC)
    assert split["best_lat"] == pytest.approx(11.0)
    assert split["best_lon"] == pytest.approx(21.0)

    provenance = pl.read_database_uri(
        """
        select m.addr::text as addr, m.blob_counts::text as blob_counts
        from sighting_minutes m
        where m.minute = timestamp '2026-02-01 00:00:00+00'
        order by m.addr::text
        """,
        database_url,
        engine="adbc",
    ).to_dicts()
    counts = [json.loads(row["blob_counts"]) for row in provenance]
    assert [len(row) for row in counts] == [2, 2, 1]
    assert [sum(row.values()) for row in counts] == [2, 2, 2]


def test_structures_and_payloads_are_stored_once(database_url: str) -> None:
    raw = "02010605094e616d6505ff4c000103"
    rows = [
        _row("AA:00:00:00:00:11", "2026-02-02T00:00:00.000Z", raw, rssi=-50),
        _row("AA:00:00:00:00:11", "2026-02-02T00:00:20.000Z", raw, rssi=-60),
    ]
    assert _ingest(database_url, "gs://t/dedupe/first.jsonl", rows) == 2
    assert (
        _ingest(
            database_url,
            "gs://t/dedupe/second.jsonl",
            [_row("AA:00:00:00:00:12", "2026-02-02T00:00:10.000Z", raw, rssi=-70)],
        )
        == 1
    )

    payloads = pl.read_database_uri(
        """
        select
          p.id,
          p.sightings,
          p.zero_padding,
          p.tail,
          cardinality(p.structure_ids) as structure_count,
          (
            select count(*)::int
            from unnest(p.structure_ids) as structure(structure_id)
            join ad_structures s on s.id = structure.structure_id
          ) as stored_structures,
          (
            select count(distinct (s.ad_type, s.data))::int
            from unnest(p.structure_ids) as structure(structure_id)
            join ad_structures s on s.id = structure.structure_id
          ) as distinct_structures
        from payloads p
        where p.sha256 = sha256(decode($1, 'hex'))
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [raw]},
    ).to_dicts()

    assert len(payloads) == 1
    assert {key: value for key, value in payloads[0].items() if key != "id"} == {
        "sightings": 3,
        "zero_padding": 0,
        "tail": None,
        "structure_count": 3,
        "stored_structures": 3,
        "distinct_structures": 3,
    }

    structures = pl.read_database_uri(
        """
        select s.ad_type, encode(s.data, 'hex') as data, s.local_name, s.company_id, s.uuids
        from ad_structures s
        where s.id in (
          select unnest(structure_ids) from payloads where sha256 = sha256(decode($1, 'hex'))
        )
        order by s.ad_type, s.data
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [raw]},
    ).to_dicts()
    assert structures == [
        {
            "ad_type": 1,
            "data": "06",
            "local_name": None,
            "company_id": None,
            "uuids": None,
        },
        {
            "ad_type": 9,
            "data": "4e616d65",
            "local_name": "Name",
            "company_id": None,
            "uuids": None,
        },
        {
            "ad_type": 255,
            "data": "4c000103",
            "local_name": None,
            "company_id": 76,
            "uuids": None,
        },
    ]

    minute_count = pl.read_database_uri(
        """
        select count(*)::int as count
        from sighting_minutes
        where minute = timestamp '2026-02-02 00:00:00+00'
        """,
        database_url,
        engine="adbc",
    ).item()
    assert minute_count == 2


def test_payload_raw_round_trips_padding_and_truncated_tail(database_url: str) -> None:
    raws = ["020106", "0201060000", "02010603ff"]
    rows = [
        _row("AA:00:00:00:00:21", "2026-02-03T00:00:00.000Z", raws[0], rssi=-50),
        _row("AA:00:00:00:00:22", "2026-02-03T00:00:10.000Z", raws[1], rssi=-51),
        _row("AA:00:00:00:00:23", "2026-02-03T00:00:20.000Z", raws[2], rssi=-52),
    ]
    assert _ingest(database_url, "gs://t/roundtrip/scans.jsonl", rows) == 3

    expected = {
        "020106": {"zero_padding": 0, "tail": None, "rebuilt": "020106"},
        "0201060000": {"zero_padding": 2, "tail": None, "rebuilt": "0201060000"},
        "02010603ff": {"zero_padding": 0, "tail": "03ff", "rebuilt": "02010603ff"},
    }
    for raw, fields in expected.items():
        stored = pl.read_database_uri(
            """
            select
              p.zero_padding,
              encode(p.tail, 'hex') as tail,
              encode(payload_raw(p.id), 'hex') as rebuilt
            from payloads p
            where p.sha256 = sha256(decode($1, 'hex'))
            """,
            database_url,
            engine="adbc",
            execute_options={"parameters": [raw]},
        ).to_dicts()
        assert stored == [fields]

    mismatched = pl.read_database_uri(
        """
        select count(*)::int as count
        from payloads p
        where sha256(payload_raw(p.id)) is distinct from p.sha256
        """,
        database_url,
        engine="adbc",
    ).item()
    assert mismatched == 0


def test_store_payloads_rejects_a_payload_that_does_not_rebuild(
    database_url: str,
) -> None:
    raw = "020107"
    _store_payloads(database_url, raw)

    with db.session(database_url, autocommit=True) as session:
        session.cursor.execute(
            """
            update payloads
            set zero_padding = zero_padding + 1
            where sha256 = sha256(decode($1, 'hex'))
            """,
            [raw],
        )

    with pytest.raises(RuntimeError, match="do not rebuild"):
        _store_payloads(database_url, raw)


def test_partition_routing_and_default_partition(database_url: str) -> None:
    assert (
        _ingest(
            database_url,
            "gs://t/partitions/monthly.jsonl",
            [_row("AA:00:00:00:00:31", "2026-02-04T00:00:00.000Z", "020106", rssi=-50)],
        )
        == 1
    )

    routed = pl.read_database_uri(
        """
        select tableoid::regclass::text as partition
        from sighting_minutes
        where minute = timestamp '2026-02-04 00:00:00+00'
        """,
        database_url,
        engine="adbc",
    ).item()
    assert routed == "sighting_minutes_y2026m02"

    created = pl.read_database_uri(
        "select ensure_sighting_partition(date '2099-05-01') as created",
        database_url,
        engine="adbc",
    ).item()
    assert created is True

    # A minute outside every monthly partition stays in the default partition.
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            insert into sighting_minutes (minute, addr, payload_id, blob_counts, sightings)
            select timestamp '2099-06-01 00:00:00+00', 'aa:00:00:00:00:32', p.id,
                   '{"1": 1}'::jsonb, 1
            from payloads p
            where p.sha256 = sha256(decode('020106', 'hex'))
            """
        )

    default_rows = pl.read_database_uri(
        """
        select count(*)::int as count
        from sighting_minutes_default
        where minute = timestamp '2099-06-01 00:00:00+00'
        """,
        database_url,
        engine="adbc",
    ).item()
    assert default_rows == 1

    # A month the default partition already holds cannot get its own partition.
    blocked = pl.read_database_uri(
        "select ensure_sighting_partition(date '2099-06-01') as created",
        database_url,
        engine="adbc",
    ).item()
    assert blocked is False


def test_minute_extent_check_rejects_invalid_coordinates(database_url: str) -> None:
    """The minute row keeps the range contract of the old coordinate checks."""
    assert (
        _ingest(
            database_url,
            "gs://t/extent/seed.jsonl",
            [_row("AA:00:00:00:00:41", "2026-02-05T00:00:00.000Z", "020106", rssi=-50)],
        )
        == 1
    )

    invalid_extents = [
        "'NaN'::float8",
        "'Infinity'::float8",
        "'-Infinity'::float8",
        "91::float8",
        "-91::float8",
    ]
    for extent in invalid_extents:
        with pytest.raises(Exception, match="sighting_minutes_extent_range"):
            with db.session(database_url) as session:
                session.cursor.execute(
                    f"""
                    insert into sighting_minutes
                      (minute, addr, payload_id, blob_counts, sightings, min_lat)
                    select timestamp '2026-02-05 01:00:00+00', 'aa:00:00:00:00:42', p.id,
                           '{{}}'::jsonb, 1, {extent}
                    from payloads p
                    where p.sha256 = sha256(decode('020106', 'hex'))
                    """
                )
