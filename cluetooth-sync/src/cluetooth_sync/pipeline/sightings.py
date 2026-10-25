"""Write path shared by ingest, rebuild and backfill.

All three reduce to: put the distinct raw advertisements of a batch into a
temporary `raws` table, store them in the payload dictionary, put the
normalized sightings into a temporary `batch` table, and merge that batch into
sighting_minutes (plus payload stats, except for rebuild).
"""

import threading
from datetime import date
from uuid import uuid4

import polars as pl

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.queries import read_query, stage_query

# Months whose sighting_minutes partition this process already ensured.
_ENSURED_MONTHS: set[date] = set()
_ENSURED_MONTHS_LOCK = threading.Lock()


def temp_table_name(kind: str) -> str:
    return f"{kind}_{uuid4().hex}"


def ensure_partitions(database_url: str, months: set[date]) -> None:
    """Create missing monthly partitions in a short transaction of their own."""
    with _ENSURED_MONTHS_LOCK:
        missing = sorted(months - _ENSURED_MONTHS)
    if not missing:
        return
    with db.session(database_url, autocommit=True) as session:
        for month in missing:
            session.cursor.execute(
                read_query("sightings/ensure_partition.sql"), [month]
            )
            session.cursor.fetch_polars()
    with _ENSURED_MONTHS_LOCK:
        _ENSURED_MONTHS.update(missing)


def months_between(first_day: date, last_day: date) -> set[date]:
    """First-of-month dates from `first_day` through `last_day`."""
    months: set[date] = set()
    month = date(first_day.year, first_day.month, 1)
    while month <= last_day:
        months.add(month)
        month = date(month.year + month.month // 12, month.month % 12 + 1, 1)
    return months


def frame_months(frame: pl.DataFrame) -> set[date]:
    return set(
        frame.select(pl.col("scanned_at").dt.truncate("1mo").dt.date().unique())
        .to_series()
        .to_list()
    )


def create_raws_table(session: db.DatabaseSession) -> str:
    raws = temp_table_name("raws")
    session.cursor.execute(stage_query("payloads/raws_table.sql", raws=raws))
    return raws


def store_payloads(session: db.DatabaseSession, raws: str) -> None:
    """Add unseen structures and payloads of `raws`; fail unless all round-trip."""
    session.cursor.execute(stage_query("payloads/insert_structures.sql", raws=raws))
    session.cursor.execute(stage_query("payloads/insert_payloads.sql", raws=raws))
    session.cursor.execute(stage_query("payloads/verify_raws.sql", raws=raws))
    mismatched = int(session.cursor.fetch_polars().item(0, "mismatched"))
    if mismatched:
        raise RuntimeError(f"{mismatched} payload(s) do not rebuild to their exact raw")


def create_batch_table(session: db.DatabaseSession) -> str:
    batch = temp_table_name("batch")
    session.cursor.execute(stage_query("sightings/batch_table.sql", batch=batch))
    return batch


def batch_count(session: db.DatabaseSession, batch: str) -> int:
    session.cursor.execute(stage_query("sightings/batch_count.sql", batch=batch))
    return int(session.cursor.fetch_polars().item(0, "row_count"))


def merge_batch(
    session: db.DatabaseSession,
    batch: str,
    *,
    update_payload_stats: bool,
) -> None:
    session.cursor.execute(stage_query("sightings/merge_minutes.sql", batch=batch))
    if update_payload_stats:
        session.cursor.execute(stage_query("payloads/update_stats.sql", batch=batch))
