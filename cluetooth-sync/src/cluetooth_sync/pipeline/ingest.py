import io
import sys
import time
from uuid import uuid4

import adbc_driver_postgresql.dbapi as pg_dbapi
import polars as pl
from polars._typing import SchemaDict

from cluetooth_sync.pipeline.legacy_adapter import (
    is_legacy_scan_blob,
    read_legacy_scan_jsonl_bytes,
)
from cluetooth_sync.pipeline.queries import read_query, scan_insert_from_stage

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
MAX_INSERT_ATTEMPTS = 5
RETRYABLE_SQLSTATES = ("40P01", "40001")


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


def insert_prepared_scans(database_url: str, scans: pl.DataFrame) -> int:
    if scans.is_empty():
        return 0

    unique_blobs = scans.get_column("blob").unique()
    if len(unique_blobs) != 1 or unique_blobs.null_count() != 0:
        raise ValueError("prepared scans must contain exactly one non-null blob URI")

    gcs_blob_uri: str = unique_blobs.item()

    for attempt in range(1, MAX_INSERT_ATTEMPTS + 1):
        try:
            _insert_prepared_scans_once(database_url, scans, gcs_blob_uri)
            return scans.height
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

            _mark_blob_failed(database_url, gcs_blob_uri, exc)
            print(f"failed {gcs_blob_uri}: {exc}", file=sys.stderr, flush=True)
            raise

    raise AssertionError("unreachable insert retry state")


def _insert_prepared_scans_once(
    database_url: str,
    scans: pl.DataFrame,
    gcs_blob_uri: str,
) -> None:
    connection = pg_dbapi.connect(database_url, autocommit=False)
    cursor = connection.cursor()
    staging_table = f"scan_stage_{uuid4().hex}"

    try:
        cursor.execute(read_query("blobs/ensure_processing.sql"), [gcs_blob_uri])
        scans.write_database(
            table_name=staging_table,
            connection=connection,
            if_table_exists="replace",
            engine="adbc",
            engine_options={"temporary": True},
        )
        cursor.execute(scan_insert_from_stage(staging_table))
        cursor.execute(read_query("blobs/mark_succeeded.sql"), [gcs_blob_uri])
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        cursor.close()
        connection.close()


def _is_retryable_insert_error(exc: Exception) -> bool:
    message = str(exc)
    return any(f"SQLSTATE: {sqlstate}" in message for sqlstate in RETRYABLE_SQLSTATES)


def _mark_blob_failed(database_url: str, gcs_blob_uri: str, exc: Exception) -> None:
    failure_connection = pg_dbapi.connect(database_url, autocommit=True)
    failure_cursor = failure_connection.cursor()
    try:
        failure_cursor.execute(
            read_query("blobs/mark_failed.sql"),
            (gcs_blob_uri, str(exc)),
        )
    finally:
        failure_cursor.close()
        failure_connection.close()
