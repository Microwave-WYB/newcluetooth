import io
import json
import sys
import time
from collections.abc import Callable

import polars as pl
from polars._typing import SchemaDict

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.legacy_adapter import (
    is_legacy_scan_blob,
    legacy_blob_identity,
    read_legacy_scan_jsonl_bytes,
)
from cluetooth_sync.pipeline.queries import read_query, stage_query
from cluetooth_sync.pipeline.sightings import (
    batch_count,
    create_batch_table,
    create_raws_table,
    ensure_partitions,
    frame_months,
    merge_batch,
    store_payloads,
    temp_table_name,
)

SCAN_POLARS_SCHEMA: SchemaDict = {
    "addr": pl.Utf8,
    "rssi": pl.Int64,
    "scanned_at": pl.Utf8,
    "raw": pl.Utf8,
    "local_name": pl.Utf8,
    "tx_power": pl.Int64,
    "is_connectable": pl.Boolean,
    "lat": pl.Float64,
    "lon": pl.Float64,
    "accuracy": pl.Float64,
}
# Producer payload v3 columns of the stage; legacy and v2 sightings have none.
STAGE_V3_SCHEMA: SchemaDict = {
    "is_legacy": pl.Boolean,
    "primary_phy": pl.Int64,
    "secondary_phy": pl.Int64,
    "adv_sid": pl.Int64,
    "adv_tx_power": pl.Int64,
    "periodic_interval": pl.Int64,
    "data_status": pl.Int64,
    "addr_type": pl.Int64,
}
MAX_INSERT_ATTEMPTS = 5
RETRYABLE_SQLSTATES = ("40P01", "40001")
# The ADBC driver cannot bind a null parameter, so callers send these sentinels
# and the SQL restores the null (see blobs/ensure_ingest.sql).
NO_UPLOADER_ID = -1


def ingest_scan_jsonl_bytes(
    database_url: str, blob_bytes: bytes, gcs_blob_uri: str
) -> int:
    scans = prepare_scan_jsonl_bytes(blob_bytes, gcs_blob_uri)
    return insert_prepared_scans(database_url, scans)


def prepare_scan_jsonl_bytes(blob_bytes: bytes, gcs_blob_uri: str) -> pl.DataFrame:
    if is_legacy_scan_blob(gcs_blob_uri):
        scans = read_legacy_scan_jsonl_bytes(blob_bytes)
    else:
        scans = pl.read_ndjson(io.BytesIO(blob_bytes), schema=SCAN_POLARS_SCHEMA)
    if scans.is_empty():
        return scans

    rssi = pl.col("rssi").cast(pl.Int64, strict=True)
    scans = scans.select(
        pl.col("addr").cast(pl.Utf8, strict=True).str.to_uppercase(),
        pl.when(rssi == 127)
        .then(pl.lit(None, dtype=pl.Int64))
        .otherwise(rssi)
        .alias("rssi"),
        pl.col("scanned_at")
        .cast(pl.Utf8, strict=True)
        .str.to_datetime(
            format="%Y-%m-%dT%H:%M:%S%.f%#z",
            time_zone="UTC",
            strict=True,
        )
        .alias("scanned_at"),
        pl.col("raw").cast(pl.Utf8, strict=True).str.to_lowercase(),
        pl.col("local_name").cast(pl.Utf8, strict=True).str.replace_all("\x00", ""),
        pl.col("tx_power").cast(pl.Int64, strict=True),
        pl.col("is_connectable").cast(pl.Boolean, strict=True),
        pl.col("lat").cast(pl.Float64, strict=True),
        pl.col("lon").cast(pl.Float64, strict=True),
        pl.col("accuracy").cast(pl.Float64, strict=True),
    )

    return scans.with_columns(pl.lit(gcs_blob_uri).alias("blob"))


def insert_prepared_scans(
    database_url: str,
    scans: pl.DataFrame,
    *,
    meta: dict[str, str] | None = None,
    mark_failure: bool = True,
    skip_if_succeeded: bool = False,
) -> int:
    """Insert one blob's scans; return inserted rows (0 if skipped as done).

    `meta` is the producer's footer metadata (v3), stored in `blobs.meta`.
    """
    if scans.is_empty():
        return 0

    unique_blobs = scans.get_column("blob").unique()
    if len(unique_blobs) != 1 or unique_blobs.null_count() != 0:
        raise ValueError("prepared scans must contain exactly one non-null blob URI")

    gcs_blob_uri: str = unique_blobs.item()
    return run_with_retries(
        database_url,
        gcs_blob_uri,
        lambda: _insert_prepared_scans_once(
            database_url, scans, gcs_blob_uri, skip_if_succeeded, meta
        ),
        mark_failure=mark_failure,
    )


def run_with_retries[T](
    database_url: str,
    gcs_blob_uri: str,
    insert_once: Callable[[], T],
    *,
    mark_failure: bool,
) -> T:
    """Run one blob's ingest transaction, retrying deadlocks/serialization failures."""
    for attempt in range(1, MAX_INSERT_ATTEMPTS + 1):
        try:
            return insert_once()
        except Exception as exc:
            if _is_retryable_insert_error(exc) and attempt < MAX_INSERT_ATTEMPTS:
                sleep_seconds = 0.1 * (2 ** (attempt - 1))
                print(
                    f"retry {attempt}/{MAX_INSERT_ATTEMPTS} {gcs_blob_uri}",
                    file=sys.stderr,
                    flush=True,
                )
                time.sleep(sleep_seconds)
                continue

            if mark_failure:
                mark_blob_failed(database_url, gcs_blob_uri, exc)
            print(f"failed {gcs_blob_uri}: {exc}", file=sys.stderr, flush=True)
            raise

    raise AssertionError("unreachable insert retry state")


def stage_frame(scans: pl.DataFrame) -> pl.DataFrame:
    """Prepared blob frame -> stage columns (per-row local_name is not stored)."""
    if "session_id" not in scans.columns:
        scans = scans.with_columns(pl.lit(None, dtype=pl.String).alias("session_id"))
    scans = scans.with_columns(
        pl.lit(None, dtype=dtype).alias(name)
        for name, dtype in STAGE_V3_SCHEMA.items()
        if name not in scans.columns
    )
    return scans.select(
        "addr",
        "rssi",
        "scanned_at",
        "raw",
        "tx_power",
        "is_connectable",
        "lat",
        "lon",
        "accuracy",
        "session_id",
        *STAGE_V3_SCHEMA,
    )


def write_stage(session: db.DatabaseSession, stage: pl.DataFrame) -> str:
    staging_table = temp_table_name("scan_stage")
    stage.write_database(
        table_name=staging_table,
        connection=session.connection,
        if_table_exists="replace",
        engine="adbc",
        engine_options={"temporary": True},
    )
    return staging_table


def load_stage_batch(
    session: db.DatabaseSession,
    stage: pl.DataFrame,
    *,
    blob_id: int,
    uploader_id: int | None,
) -> str:
    """Stage one blob's sightings, store its payloads, return the batch table."""
    staging_table = write_stage(session, stage)
    raws = create_raws_table(session)
    session.cursor.execute(
        stage_query("payloads/collect_from_stage.sql", raws=raws, stage=staging_table)
    )
    store_payloads(session, raws)
    batch = create_batch_table(session)
    session.cursor.execute(
        stage_query("sightings/batch_from_stage.sql", batch=batch, stage=staging_table),
        [blob_id, NO_UPLOADER_ID if uploader_id is None else uploader_id],
    )
    loaded = batch_count(session, batch)
    if loaded != stage.height:
        raise RuntimeError(f"batched {loaded} of {stage.height} sightings")
    return batch


def _insert_prepared_scans_once(
    database_url: str,
    scans: pl.DataFrame,
    gcs_blob_uri: str,
    skip_if_succeeded: bool,
    meta: dict[str, str] | None,
) -> int:
    stage = stage_frame(scans)
    ensure_partitions(database_url, frame_months(stage))
    uploader, app_version = blob_identity(gcs_blob_uri, meta)
    session_ids = stage.get_column("session_id").drop_nulls().unique()
    blob_session_id = session_ids.item() if len(session_ids) == 1 else None

    with db.session(database_url) as session:
        session.cursor.execute(read_query("sightings/ingest_lock.sql"))
        session.cursor.fetch_polars()
        lock_blob(session, gcs_blob_uri)
        uploader_id = ensure_uploader(session, uploader)
        blob_id, already_succeeded = ensure_blob(
            session, gcs_blob_uri, uploader_id, app_version, blob_session_id, meta
        )
        if skip_if_succeeded and already_succeeded:
            # Another sync process committed this blob after our discovery.
            print(f"skip {gcs_blob_uri}: already ingested", flush=True)
            return 0
        batch = load_stage_batch(
            session, stage, blob_id=blob_id, uploader_id=uploader_id
        )
        merge_batch(session, batch, update_payload_stats=True)
        session.cursor.execute(read_query("blobs/mark_succeeded.sql"), [gcs_blob_uri])
    return stage.height


def lock_blob(session: db.DatabaseSession, gcs_blob_uri: str) -> None:
    """Hold the blob's advisory lock until the session's connection closes.

    Take it before `ensure_blob` and check `success` after it: whoever waited
    here sees the other process's committed result and skips the blob.
    """
    session.cursor.execute(read_query("blobs/lock.sql"), [gcs_blob_uri])
    session.cursor.fetch_polars()


def ensure_uploader(session: db.DatabaseSession, uploader: str | None) -> int | None:
    if uploader is None:
        return None
    session.cursor.execute(read_query("uploaders/ensure.sql"), [uploader])
    session.cursor.execute(read_query("uploaders/get_id.sql"), [uploader])
    return int(session.cursor.fetch_polars().item(0, "id"))


def blob_identity(
    gcs_blob_uri: str, meta: dict[str, str] | None
) -> tuple[str | None, str | None]:
    """(uploader, app_version): from a legacy name, else from the footer."""
    uploader, app_version = legacy_blob_identity(gcs_blob_uri)
    if app_version is None and meta is not None:
        app_version = meta.get("app_version")
    return uploader, app_version


def ensure_blob(
    session: db.DatabaseSession,
    gcs_blob_uri: str,
    uploader_id: int | None,
    app_version: str | None,
    session_id: str | None,
    meta: dict[str, str] | None = None,
) -> tuple[int, bool]:
    session.cursor.execute(
        read_query("blobs/ensure_ingest.sql"),
        [
            gcs_blob_uri,
            NO_UPLOADER_ID if uploader_id is None else uploader_id,
            app_version or "",
            session_id or "",
            "" if meta is None else json.dumps(meta),
        ],
    )
    row = session.cursor.fetch_polars()
    return int(row.item(0, "id")), row.item(0, "success") is True


def _is_retryable_insert_error(exc: Exception) -> bool:
    message = str(exc)
    return any(f"SQLSTATE: {sqlstate}" in message for sqlstate in RETRYABLE_SQLSTATES)


def mark_blob_failed(database_url: str, gcs_blob_uri: str, exc: Exception) -> None:
    with db.session(database_url, autocommit=True) as session:
        session.cursor.execute(
            read_query("blobs/mark_failed.sql"),
            (gcs_blob_uri, str(exc)),
        )


def mark_blob_succeeded_empty(
    database_url: str, gcs_blob_uri: str, meta: dict[str, str] | None = None
) -> None:
    uploader, app_version = blob_identity(gcs_blob_uri, meta)
    with db.session(database_url) as session:
        lock_blob(session, gcs_blob_uri)
        uploader_id = ensure_uploader(session, uploader)
        ensure_blob(session, gcs_blob_uri, uploader_id, app_version, None, meta)
        session.cursor.execute(read_query("blobs/mark_succeeded.sql"), [gcs_blob_uri])
