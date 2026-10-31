"""Backfill: fold the old per-sighting table into sighting_minutes.

Each id range of `scans_old` is one transaction that stores the range's
payloads, merges its minute rows, maps the old WiGLE memberships onto those
minute rows and records the range in `backfill_progress`, so an interrupted run
resumes where it stopped.

Verification runs afterwards and assumes sighting_minutes holds only backfilled
data (checks 01/02/05/07 compare against `scans_old` totals), so run the
backfill before any new ingest.
"""

import time
from dataclasses import dataclass

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.queries import query_names, read_query, stage_query
from cluetooth_sync.pipeline.sightings import (
    batch_count,
    create_batch_table,
    create_raws_table,
    ensure_partitions,
    merge_batch,
    months_between,
    store_payloads,
)

PHASE = "minutes"


@dataclass(frozen=True)
class VerifyResult:
    name: str
    informational: bool
    old_value: int | None
    new_value: int | None
    seconds: float

    @property
    def ok(self) -> bool:
        return self.informational or self.old_value == self.new_value

    @property
    def state(self) -> str:
        if self.informational:
            return "info"
        return "ok" if self.ok else "FAILED"


@dataclass(frozen=True)
class BackfillResult:
    rows: int
    aggregate_seconds: float
    verify_seconds: float
    checks: tuple[VerifyResult, ...]

    @property
    def seconds(self) -> float:
        return self.aggregate_seconds + self.verify_seconds

    @property
    def ok(self) -> bool:
        return all(check.ok for check in self.checks)


def run_backfill(
    database_url: str,
    *,
    batch_size: int,
    verify_only: bool = False,
) -> BackfillResult:
    """Backfill the minute tables (unless `verify_only`) and verify them."""
    started = time.monotonic()
    rows = 0
    if not verify_only:
        rows = backfill_minutes(database_url, batch_size=batch_size)
        run_script(database_url, "backfill/enrichments.sql")

    aggregated = time.monotonic()
    checks = tuple(verify_backfill(database_url))
    verified = time.monotonic()
    aggregate_seconds = aggregated - started
    verify_seconds = verified - aggregated
    print(
        f"backfill rows={rows} aggregate_seconds={aggregate_seconds:.1f} "
        f"verify_seconds={verify_seconds:.1f} "
        f"seconds_per_million={seconds_per_million(aggregate_seconds, rows):.1f}",
        flush=True,
    )
    for check in checks:
        print(
            f"verify {check.name} {check.state} "
            f"old={check.old_value} new={check.new_value} {check.seconds:.2f}s",
            flush=True,
        )
    return BackfillResult(
        rows=rows,
        aggregate_seconds=aggregate_seconds,
        verify_seconds=verify_seconds,
        checks=checks,
    )


def seconds_per_million(seconds: float, rows: int) -> float:
    if rows <= 0:
        return 0.0
    return seconds / rows * 1_000_000


def backfill_minutes(database_url: str, *, batch_size: int) -> int:
    """Aggregate `scans_old` into sighting_minutes; return the rows it covers."""
    with db.session(database_url, autocommit=True) as session:
        session.cursor.execute(read_query("backfill/progress_table.sql"))
        session.cursor.execute(read_query("backfill/blobs.sql"))
        session.cursor.execute(read_query("backfill/source_range.sql"))
        source = session.cursor.fetch_polars().row(0, named=True)

    max_id = int(source["max_id"])
    first_day = source["first_day"]
    last_day = source["last_day"]
    if max_id == 0 or first_day is None or last_day is None:
        print("backfill: scans_old is empty", flush=True)
        return 0

    # Partitions before the loop: creating one inside a batch transaction would
    # wait on the parent table that the same transaction already touches.
    ensure_partitions(database_url, months_between(first_day, last_day))

    with db.session(database_url) as session:
        session.cursor.execute(read_query("backfill/progress_get.sql"), [PHASE])
        progress = session.cursor.fetch_polars().row(0, named=True)

    through_id = int(progress["through_id"])
    rows = int(progress["rows"])
    if progress["done"] is True:
        print(f"backfill: already done through scans_old id {through_id}", flush=True)
        return rows

    while through_id < max_id:
        end_id = min(through_id + batch_size, max_id)
        rows += _backfill_range(database_url, through_id, end_id, done=end_id >= max_id)
        print(f"backfill: scans_old id <= {end_id} rows={rows}", flush=True)
        through_id = end_id
    return rows


def _backfill_range(
    database_url: str, start_id: int, end_id: int, *, done: bool
) -> int:
    """One resumable id range, in a single transaction."""
    with db.session(database_url) as session:
        raws = create_raws_table(session)
        session.cursor.execute(
            stage_query("backfill/collect_raws.sql", raws=raws), [start_id, end_id]
        )
        store_payloads(session, raws)

        batch = create_batch_table(session)
        session.cursor.execute(
            stage_query("backfill/batch.sql", batch=batch), [start_id, end_id]
        )
        session.cursor.execute(
            read_query("backfill/count_range.sql"), [start_id, end_id]
        )
        expected = int(session.cursor.fetch_polars().item(0, "row_count"))
        batched = batch_count(session, batch)
        if batched != expected:
            raise RuntimeError(
                f"batched {batched} of {expected} scans_old rows in ({start_id}, {end_id}]"
            )

        merge_batch(session, batch, update_payload_stats=True)
        session.cursor.execute(
            stage_query("backfill/wigle_membership.sql", batch=batch)
        )
        session.cursor.execute(
            read_query("backfill/progress_set.sql"), [PHASE, end_id, batched, done]
        )
    return batched


def run_script(database_url: str, path: str) -> None:
    with db.session(database_url, autocommit=True) as session:
        session.cursor.execute(read_query(path))


def verify_backfill(database_url: str) -> list[VerifyResult]:
    """Run every `backfill/verify/*.sql` check in one snapshot."""
    results: list[VerifyResult] = []
    with db.session(database_url) as session:
        for name in query_names("backfill/verify"):
            started = time.monotonic()
            session.cursor.execute(read_query(f"backfill/verify/{name}"))
            row = session.cursor.fetch_polars().row(0, named=True)
            results.append(
                VerifyResult(
                    name=name.removesuffix(".sql"),
                    informational="_info_" in name,
                    old_value=row["old_value"],
                    new_value=row["new_value"],
                    seconds=time.monotonic() - started,
                )
            )
    return results
