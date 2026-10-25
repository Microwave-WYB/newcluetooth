"""Raw access over the GCS archive: replay sightings and rebuild minute rows.

The database keeps only minute aggregates; the uploaded objects are the raw
archive. Both commands read objects through a StorageClient (GCS, optionally
via the local mirror) and parse them with the same code as ingest.
"""

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime

import polars as pl

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.download import read_blob_bytes
from cluetooth_sync.pipeline.ingest import load_stage_batch, stage_frame
from cluetooth_sync.pipeline.orchestrate import BlobBytes, parse_blob
from cluetooth_sync.pipeline.queries import read_query
from cluetooth_sync.pipeline.sightings import (
    ensure_partitions,
    merge_batch,
    months_between,
)
from cluetooth_sync.pipeline.storage import StorageClient


@dataclass(frozen=True)
class RebuildResult:
    blobs: int
    sightings: int


def blobs_in_range(
    database_url: str,
    since: datetime,
    until: datetime,
    uploader: str | None = None,
) -> list[str]:
    """Blob URIs that contributed to minutes in [since, until)."""
    with db.session(database_url) as session:
        session.cursor.execute(
            read_query("sightings/blobs_in_range.sql"),
            [since, until, uploader or ""],
        )
        return session.cursor.fetch_polars().get_column("uri").to_list()


def _in_range(
    frame: pl.DataFrame, since: datetime | None, until: datetime | None
) -> pl.DataFrame:
    if since is not None:
        frame = frame.filter(pl.col("scanned_at") >= since)
    if until is not None:
        frame = frame.filter(pl.col("scanned_at") < until)
    return frame


def replay_sightings(
    storage_client: StorageClient,
    private_key: bytes,
    blob_uris: list[str],
    *,
    since: datetime | None = None,
    until: datetime | None = None,
) -> Iterator[pl.DataFrame]:
    """Yield each blob's raw sightings (raw as lowercase hex, plus `blob`)."""
    for uri in blob_uris:
        blob = BlobBytes(uri=uri, encrypted_bytes=read_blob_bytes(storage_client, uri))
        yield _in_range(parse_blob(blob, private_key), since, until)


def rebuild_minutes(
    database_url: str,
    storage_client: StorageClient,
    private_key: bytes,
    *,
    since: datetime,
    until: datetime,
    uploader: str | None = None,
) -> RebuildResult:
    """Regenerate sighting_minutes in [since, until) from the archived objects.

    One transaction; ingest pauses while it runs. Payload stats are left as
    they are (the same sightings are counted either way).
    """
    uploader_filter = uploader or ""
    # Partitions first, outside the locked transaction: creating one waits on
    # the parent table, which that transaction already holds.
    ensure_partitions(database_url, months_between(since.date(), until.date()))
    with db.session(database_url) as session:
        session.cursor.execute(read_query("sightings/rebuild_lock.sql"))
        session.cursor.fetch_polars()
        session.cursor.execute(
            read_query("sightings/blobs_in_range.sql"), [since, until, uploader_filter]
        )
        uris = session.cursor.fetch_polars().get_column("uri").to_list()
        session.cursor.execute(
            read_query("sightings/reset_range.sql"), [since, until, uploader_filter]
        )

        sightings = 0
        for frame in replay_sightings(
            storage_client, private_key, uris, since=since, until=until
        ):
            if frame.is_empty():
                continue
            uri = frame.get_column("blob").item(0)
            stage = stage_frame(frame)
            session.cursor.execute(read_query("blobs/get_ingest_identity.sql"), [uri])
            identity = session.cursor.fetch_polars()
            blob_id = int(identity.item(0, "id"))
            uploader_id = identity.item(0, "uploader_id")
            batch = load_stage_batch(
                session,
                stage,
                blob_id=blob_id,
                uploader_id=None if uploader_id is None else int(uploader_id),
            )
            merge_batch(session, batch, update_payload_stats=False)
            sightings += stage.height

        session.cursor.execute(read_query("sightings/delete_empty.sql"), [since, until])
    return RebuildResult(blobs=len(uris), sightings=sightings)
