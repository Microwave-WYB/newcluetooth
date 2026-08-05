import base64
import io
import re
from pathlib import Path

import polars as pl
from polars._typing import SchemaDict

from cluetooth_sync.ble import parse_raw

LEGACY_SCAN_POLARS_SCHEMA: SchemaDict = {
    "mac": pl.Utf8,
    "rssi": pl.Int64,
    "timestamp": pl.Utf8,
    "lat": pl.Float64,
    "lon": pl.Float64,
    "accuracy": pl.Float64,
    "raw": pl.Utf8,
}

LEGACY_SCAN_VERSION_PATTERN = re.compile(r"^0\.0\.[1-4](?:[-+].*)?$")


def _detect_payload_version(gcs_blob_uri: str) -> str | None:
    filename = Path(gcs_blob_uri).name
    match = re.search(
        r"_([0-9]+(?:\.[0-9]+){2}(?:[-+][A-Za-z0-9_.-]+)?)"
        r"\.jsonl(?:\.zst)?(?:\.encrypted)?$",
        filename,
    )
    if match is None:
        return None
    return match.group(1)


def is_legacy_scan_blob(gcs_blob_uri: str) -> bool:
    version = _detect_payload_version(gcs_blob_uri)
    return (
        version is not None and LEGACY_SCAN_VERSION_PATTERN.match(version) is not None
    )


def _decode_raw_bytes(raw: str | None) -> bytes | None:
    if raw is None:
        return None
    return base64.b64decode(raw)


def _decode_raw_hex(raw: str | None) -> str | None:
    raw_bytes = _decode_raw_bytes(raw)
    if raw_bytes is None:
        return None
    return raw_bytes.hex()


def _extract_local_name(raw: str | None) -> str | None:
    raw_bytes = _decode_raw_bytes(raw)
    if raw_bytes is None:
        return None

    structs = parse_raw(raw_bytes)
    name_struct = next(filter(lambda struct: struct.ad_type == 0x09, structs), None)
    if name_struct is None:
        name_struct = next(filter(lambda struct: struct.ad_type == 0x08, structs), None)
    if name_struct is None:
        return None

    name_bytes = name_struct.data.rstrip(b"\x00")
    try:
        return name_bytes.decode("utf-8") or None
    except UnicodeDecodeError:
        return None


def read_legacy_scan_jsonl_bytes(blob_bytes: bytes) -> pl.DataFrame:
    scans = pl.read_ndjson(
        io.BytesIO(blob_bytes),
        schema=LEGACY_SCAN_POLARS_SCHEMA,
    )
    if scans.is_empty():
        return scans

    return scans.select(
        pl.col("mac").alias("addr"),
        pl.col("rssi"),
        pl.col("timestamp").alias("scanned_at"),
        pl.col("raw").map_elements(_decode_raw_hex, return_dtype=pl.Utf8).alias("raw"),
        pl.col("raw")
        .map_elements(_extract_local_name, return_dtype=pl.Utf8)
        .alias("local_name"),
        pl.lit(None, dtype=pl.Int64).alias("tx_power"),
        pl.lit(None, dtype=pl.Boolean).alias("is_connectable"),
        pl.col("lat"),
        pl.col("lon"),
        pl.col("accuracy"),
    )
