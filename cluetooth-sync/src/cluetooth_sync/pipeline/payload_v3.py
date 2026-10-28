import io

import polars as pl
from polars._typing import SchemaDict

from cluetooth_sync.parquet_footer import read_parquet_key_value_occurrences

from .payload_route import PayloadRoute, PayloadRouteKind
from .payload_v2 import (
    PAYLOAD_ID_METADATA_KEY,
    PAYLOAD_SCHEMA_METADATA_KEY,
    PAYLOAD_V2_POLARS_SCHEMA,
    _optional_session_id,
    _required_metadata_value,
    _validate_payload_values,
)

PAYLOAD_SCHEMA_VERSION = "v3"
FOOTER_KEY_PREFIX = "cluetooth."
# The v2 columns, in order, then the v3 columns (docs/payload-schema-v3.md).
PAYLOAD_V3_POLARS_SCHEMA: SchemaDict = {
    **PAYLOAD_V2_POLARS_SCHEMA,
    "is_legacy": pl.Boolean,
    "primary_phy": pl.Int32,
    "secondary_phy": pl.Int32,
    "adv_sid": pl.Int32,
    "adv_tx_power": pl.Int32,
    "periodic_interval": pl.Int32,
    "data_status": pl.Int32,
    "addr_type": pl.Int32,
}
V3_COLUMNS = (
    "is_legacy",
    "primary_phy",
    "secondary_phy",
    "adv_sid",
    "adv_tx_power",
    "periodic_interval",
    "data_status",
    "addr_type",
)
# Android reports "absent" with these values; they are stored as null.
ADV_SID_ABSENT = 255
ADV_TX_POWER_ABSENT = 127
PERIODIC_INTERVAL_ABSENT = 0


def read_payload_v3_meta(
    occurrences: list[tuple[str, str | None]], route: PayloadRoute
) -> dict[str, str]:
    """`cluetooth.*` footer entries (prefix removed) after checking schema and ID."""
    schema_version = _required_metadata_value(occurrences, PAYLOAD_SCHEMA_METADATA_KEY)
    if schema_version != PAYLOAD_SCHEMA_VERSION:
        raise ValueError(
            f"expected {PAYLOAD_SCHEMA_METADATA_KEY}={PAYLOAD_SCHEMA_VERSION}, "
            f"got {schema_version!r}"
        )
    metadata_payload_id = _required_metadata_value(occurrences, PAYLOAD_ID_METADATA_KEY)
    if metadata_payload_id != route.payload_id:
        raise ValueError(
            f"footer payload ID {metadata_payload_id!r} does not match URI payload ID "
            f"{route.payload_id!r}"
        )
    meta: dict[str, str] = {}
    for key, value in occurrences:
        if not key.startswith(FOOTER_KEY_PREFIX):
            continue
        short_key = key.removeprefix(FOOTER_KEY_PREFIX)
        # Every cluetooth.* key must occur once with a value, not only the required ones.
        _required_metadata_value(occurrences, key)
        # PostgreSQL jsonb cannot hold NUL.
        meta[short_key] = (value or "").replace("\x00", "")
    return meta


def prepare_payload_v3_parquet_bytes(
    payload_bytes: bytes,
    route: PayloadRoute,
) -> tuple[pl.DataFrame, dict[str, str]]:
    """Validate a scan v3 file; return its sightings and its footer metadata."""
    if route.kind is not PayloadRouteKind.PAYLOAD_V3_PARQUET:
        raise ValueError("payload-v3 reader requires a payload-v3 route")
    if route.payload_id is None:
        raise ValueError("payload-v3 route is missing its payload ID")

    occurrences = read_parquet_key_value_occurrences(payload_bytes)
    meta = read_payload_v3_meta(occurrences, route)

    scans = pl.read_parquet(io.BytesIO(payload_bytes))
    expected_schema = pl.Schema(PAYLOAD_V3_POLARS_SCHEMA)
    if scans.schema != expected_schema:
        raise ValueError(
            f"payload-v3 schema mismatch: expected {expected_schema!r}, got {scans.schema!r}"
        )
    for column in ("addr", "scanned_at", "raw"):
        null_count = scans.get_column(column).null_count()
        if null_count != 0:
            raise ValueError(
                f"required payload-v3 column {column} contains {null_count} null value(s)"
            )

    _validate_payload_values(scans, "payload-v3")
    scans = _normalize_absent_values(scans)
    _validate_v3_values(scans)

    rssi = pl.col("rssi").cast(pl.Int64, strict=True)
    prepared = scans.select(
        pl.col("addr"),
        pl.when(rssi == 127)
        .then(pl.lit(None, dtype=pl.Int64))
        .otherwise(rssi)
        .alias("rssi"),
        pl.col("scanned_at"),
        pl.col("raw").bin.encode("hex").alias("raw"),
        pl.col("local_name").str.replace_all("\x00", ""),
        pl.col("tx_power").cast(pl.Int64, strict=True),
        pl.col("is_connectable"),
        pl.col("lat"),
        pl.col("lon"),
        pl.col("accuracy"),
        pl.col("is_legacy"),
        *(pl.col(name).cast(pl.Int64, strict=True) for name in V3_COLUMNS[1:]),
        pl.lit(route.uri, dtype=pl.String).alias("blob"),
        pl.lit(
            _optional_session_id(occurrences),
            dtype=pl.String,
        ).alias("session_id"),
    )
    return prepared, meta


def _normalize_absent_values(scans: pl.DataFrame) -> pl.DataFrame:
    return scans.with_columns(
        pl.when(pl.col("adv_sid") == ADV_SID_ABSENT)
        .then(None)
        .otherwise(pl.col("adv_sid"))
        .alias("adv_sid"),
        pl.when(pl.col("adv_tx_power") == ADV_TX_POWER_ABSENT)
        .then(None)
        .otherwise(pl.col("adv_tx_power"))
        .alias("adv_tx_power"),
        pl.when(pl.col("periodic_interval") == PERIODIC_INTERVAL_ABSENT)
        .then(None)
        .otherwise(pl.col("periodic_interval"))
        .alias("periodic_interval"),
    )


def _validate_v3_values(scans: pl.DataFrame) -> None:
    def outside(column: str, low: int, high: int) -> pl.Expr:
        return pl.col(column).is_not_null() & ~pl.col(column).is_between(low, high)

    checks: list[tuple[str, pl.Expr]] = [
        (
            "primary_phy must be 1 (1M) or 3 (Coded)",
            pl.col("primary_phy").is_not_null() & ~pl.col("primary_phy").is_in([1, 3]),
        ),
        ("secondary_phy must be 0 through 3", outside("secondary_phy", 0, 3)),
        ("adv_sid must be 0 through 15 (255 means absent)", outside("adv_sid", 0, 15)),
        (
            "adv_tx_power must be -127 through 126 (127 means absent)",
            outside("adv_tx_power", -127, 126),
        ),
        (
            "periodic_interval must be 1 through 65536 (0 means none)",
            outside("periodic_interval", 1, 65536),
        ),
        ("data_status must be 0 through 2", outside("data_status", 0, 2)),
        ("addr_type must be 0 through 255", outside("addr_type", 0, 255)),
    ]
    for message, expression in checks:
        invalid_count = scans.select(expression.fill_null(False).sum()).item()
        if invalid_count:
            raise ValueError(
                f"payload-v3 value validation failed: {message}; "
                f"found {invalid_count} row(s)"
            )
