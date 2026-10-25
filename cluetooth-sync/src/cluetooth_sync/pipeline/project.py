from dataclasses import dataclass

import cluetooth_sync.pipeline.db as db

from cluetooth_sync.pipeline.queries import read_query


@dataclass(frozen=True)
class ProjectionRange:
    previous_scans_through_id: int
    scans_through_id: int


def safe_scan_watermark(database_url: str) -> int:
    with db.session(database_url) as session:
        session.cursor.execute(read_query("advs/safe_watermark.sql"))
        session.cursor.fetch_polars()
        session.cursor.execute(read_query("advs/max_scan_id.sql"))
        return int(session.cursor.fetch_polars().item(0, "max_scan_id"))


def project_advs(database_url: str) -> ProjectionRange:
    # Safe while ingest is running: the watermark excludes uncommitted scan ids.
    watermark = safe_scan_watermark(database_url)
    with db.session(database_url) as session:
        session.cursor.execute(read_query("advs/project.sql"), [watermark])
        rows = session.cursor.fetch_polars()
    if rows.is_empty():
        return ProjectionRange(watermark, watermark)
    return ProjectionRange(
        int(rows.item(0, "previous_scans_through_id")),
        int(rows.item(0, "target_scans_through_id")),
    )
