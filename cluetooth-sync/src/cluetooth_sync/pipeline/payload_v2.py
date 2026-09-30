import io

import polars as pl
from polars._typing import SchemaDict

from cluetooth_sync.parquet_footer import read_parquet_key_value_occurrences

from .payload_route import PayloadRoute, PayloadRouteKind

PAYLOAD_SCHEMA_METADATA_KEY = "cluetooth.payload_schema"
PAYLOAD_ID_METADATA_KEY = "cluetooth.payload_id"
PAYLOAD_SCHEMA_VERSION = "v2"
PAYLOAD_V2_POLARS_SCHEMA: SchemaDict = {
    "addr": pl.String,
    "rssi": pl.Int32,
    "scanned_at": pl.Datetime(time_unit="ms", time_zone="UTC"),
    "raw": pl.Binary,
    "local_name": pl.String,
    "tx_power": pl.Int32,
    "is_connectable": pl.Boolean,
    "lat": pl.Float64,
    "lon": pl.Float64,
    "accuracy": pl.Float32,
}


def _required_metadata_value(
    occurrences: list[tuple[str, str | None]], key: str
) -> str:
    values = [value for candidate, value in occurrences if candidate == key]
    if len(values) != 1:
        raise ValueError(
            f"required Parquet footer metadata key {key!r} must occur exactly once; "
            f"found {len(values)}"
        )
    value = values[0]
    if value is None:
        raise ValueError(f"required Parquet footer metadata key {key!r} has no value")
    return value


def prepare_payload_v2_parquet_bytes(
    payload_bytes: bytes,
    route: PayloadRoute,
) -> pl.DataFrame:
    if route.kind is not PayloadRouteKind.PAYLOAD_V2_PARQUET:
        raise ValueError("payload-v2 reader requires a payload-v2 route")
    if route.payload_id is None:
        raise ValueError("payload-v2 route is missing its payload ID")

    metadata_occurrences = read_parquet_key_value_occurrences(payload_bytes)
    schema_version = _required_metadata_value(
        metadata_occurrences, PAYLOAD_SCHEMA_METADATA_KEY
    )
    if schema_version != PAYLOAD_SCHEMA_VERSION:
        raise ValueError(
            f"expected {PAYLOAD_SCHEMA_METADATA_KEY}={PAYLOAD_SCHEMA_VERSION}, "
            f"got {schema_version!r}"
        )
    metadata_payload_id = _required_metadata_value(
        metadata_occurrences, PAYLOAD_ID_METADATA_KEY
    )
    if metadata_payload_id != route.payload_id:
        raise ValueError(
            f"footer payload ID {metadata_payload_id!r} does not match URI payload ID "
            f"{route.payload_id!r}"
        )

    scans = pl.read_parquet(io.BytesIO(payload_bytes))
    expected_schema = pl.Schema(PAYLOAD_V2_POLARS_SCHEMA)
    if scans.schema != expected_schema:
        raise ValueError(
            f"payload-v2 schema mismatch: expected {expected_schema!r}, got {scans.schema!r}"
        )
    for column in ("addr", "scanned_at", "raw"):
        null_count = scans.get_column(column).null_count()
        if null_count != 0:
            raise ValueError(
                f"required payload-v2 column {column} contains {null_count} null value(s)"
            )

    _validate_payload_values(scans)
    rssi = pl.col("rssi").cast(pl.Int64, strict=True)
    return scans.select(
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
        pl.lit(route.uri, dtype=pl.String).alias("blob"),
    )


def _validate_payload_values(scans: pl.DataFrame) -> None:
    canonical_mac = r"^[0-9A-F]{2}(?::[0-9A-F]{2}){5}$"
    checks: list[tuple[str, pl.Expr]] = [
        (
            "addr must be canonical uppercase XX:XX:XX:XX:XX:XX",
            ~pl.col("addr").str.contains(canonical_mac),
        ),
        (
            "rssi is outside signed-smallint range",
            pl.col("rssi").is_not_null() & ~pl.col("rssi").is_between(-32768, 32767),
        ),
        (
            "tx_power is outside signed-smallint range",
            pl.col("tx_power").is_not_null()
            & ~pl.col("tx_power").is_between(-32768, 32767),
        ),
        (
            "lat and lon must be present together",
            pl.col("lat").is_null() != pl.col("lon").is_null(),
        ),
        (
            "lat must be finite and between -90 and 90",
            pl.col("lat").is_not_null()
            & (~pl.col("lat").is_finite() | ~pl.col("lat").is_between(-90.0, 90.0)),
        ),
        (
            "lon must be finite and between -180 and 180",
            pl.col("lon").is_not_null()
            & (~pl.col("lon").is_finite() | ~pl.col("lon").is_between(-180.0, 180.0)),
        ),
        (
            "accuracy requires coordinates",
            pl.col("accuracy").is_not_null() & pl.col("lat").is_null(),
        ),
        (
            "accuracy must be finite and nonnegative",
            pl.col("accuracy").is_not_null()
            & (~pl.col("accuracy").is_finite() | (pl.col("accuracy") < 0.0)),
        ),
    ]
    for message, expression in checks:
        invalid_count = scans.select(expression.fill_null(False).sum()).item()
        if invalid_count:
            raise ValueError(
                f"payload-v2 value validation failed: {message}; found {invalid_count} row(s)"
            )
