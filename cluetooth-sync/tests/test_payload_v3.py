from __future__ import annotations

import io
import json
from datetime import UTC, datetime
from typing import Any

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox

from cluetooth_sync.pipeline import (
    PayloadRouteKind,
    UnsupportedPayloadPathError,
    UnsupportedPayloadSchemaError,
    prepare_payload_v3_parquet_bytes,
    rebuild_minutes,
    route_payload_uri,
)
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob
from cluetooth_sync.pipeline.payload_v3 import (
    PAYLOAD_V3_POLARS_SCHEMA,
    read_payload_v3_meta,
)

PRIVATE_KEY = PrivateKey(bytes(range(32)))
RAW_EXT = bytes.fromhex("02010605094e616d6505ff4c000102")


def _uri(suffix: str, root: str = "scans/v3", ext: str = "parquet") -> str:
    # UUIDv7 time 0x0195c9207c00 is 2025-03-24 UTC, as in the v2 tests.
    return (
        f"gs://test-bucket/{root}/2025/03/24/"
        f"0195c920-7c00-7abc-8def-0123456789{suffix}.{ext}.encrypted"
    )


def _payload_id(uri: str) -> str:
    return uri.rsplit("/", 1)[-1].removesuffix(".parquet.encrypted")


FOOTER = {
    "cluetooth.device_model": "Pixel 8",
    "cluetooth.android_version": "15",
    "cluetooth.app_version": "0.2.0",
    "cluetooth.le_extended_advertising_supported": "true",
    "cluetooth.le_coded_phy_supported": "true",
    "cluetooth.le_max_advertising_data_length": "1650",
    "cluetooth.scan_phy": "all_supported",
}


def _frame(rows: list[dict[str, Any]]) -> pl.DataFrame:
    defaults: dict[str, Any] = {
        "addr": "AA:00:00:03:00:01",
        "rssi": -60,
        "scanned_at": datetime(2031, 1, 1, 12, 0, 5, tzinfo=UTC),
        "raw": RAW_EXT,
        "local_name": None,
        "tx_power": None,
        "is_connectable": True,
        "lat": None,
        "lon": None,
        "accuracy": None,
        **{
            name: None
            for name in (
                "is_legacy",
                "primary_phy",
                "secondary_phy",
                "adv_sid",
                "adv_tx_power",
                "periodic_interval",
                "data_status",
                "addr_type",
            )
        },
    }
    return pl.DataFrame(
        [{**defaults, **row} for row in rows], schema=PAYLOAD_V3_POLARS_SCHEMA
    )


def _parquet(
    frame: pl.DataFrame,
    uri: str,
    *,
    footer: dict[str, str] | None = None,
    schema: str = "v3",
) -> bytes:
    output = io.BytesIO()
    frame.write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": schema,
            "cluetooth.payload_id": _payload_id(uri),
            **(FOOTER if footer is None else footer),
        },
    )
    return output.getvalue()


def _encrypt(plaintext: bytes) -> bytes:
    return SealedBox(PRIVATE_KEY.public_key).encrypt(plaintext)


def _ingest(database_url: str, uri: str, plaintext: bytes) -> int | None:
    return _ingest_blob(
        database_url, bytes(PRIVATE_KEY), BlobBytes(uri, _encrypt(plaintext))
    )


V3_URI = _uri("b1")


def test_v3_route_is_canonical_and_dated() -> None:
    route = route_payload_uri(V3_URI)
    assert route.kind is PayloadRouteKind.PAYLOAD_V3_PARQUET
    assert route.payload_id == _payload_id(V3_URI)
    assert route.utc_date == "2025-03-24"


def test_gatt_route_is_canonical_and_dated() -> None:
    uri = _uri("c1", root="gatt/v1", ext="json")
    route = route_payload_uri(uri)
    assert route.kind is PayloadRouteKind.GATT_V1_JSON
    assert route.payload_id == "0195c920-7c00-7abc-8def-0123456789c1"
    assert route.utc_date == "2025-03-24"


@pytest.mark.parametrize(
    ("uri", "error_type"),
    [
        # Wrong extension or root for the type.
        (_uri("b1", ext="json"), UnsupportedPayloadPathError),
        (_uri("c1", root="gatt/v1", ext="parquet"), UnsupportedPayloadPathError),
        # UUID/date rules apply as for v2.
        (
            "gs://test-bucket/scans/v3/2025/03/23/"
            "0195c920-7c00-7abc-8def-0123456789b1.parquet.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://test-bucket/gatt/v1/2025/03/24/"
            "550e8400-e29b-41d4-a716-446655440000.json.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://test-bucket/scans/v3/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        # Other GATT paths and versions stay rejected.
        (
            "gs://test-bucket/gatt/v2/2025/03/24/"
            "0195c920-7c00-7abc-8def-0123456789c1.json.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        ("gs://test-bucket/gatt/device.json.encrypted", UnsupportedPayloadPathError),
        (
            "gs://test-bucket/gatt/v1/../v1/2025/03/24/"
            "0195c920-7c00-7abc-8def-0123456789c1.json.encrypted",
            UnsupportedPayloadPathError,
        ),
        # A gatt object is not a scan under scans/.
        (
            "gs://test-bucket/scans/gatt/v1/2025/03/24/"
            "0195c920-7c00-7abc-8def-0123456789c1.json.encrypted",
            UnsupportedPayloadPathError,
        ),
    ],
)
def test_v3_and_gatt_route_error_matrix(uri: str, error_type: type[ValueError]) -> None:
    with pytest.raises(error_type):
        route_payload_uri(uri)


def test_valid_v3_file_prepares_columns_and_footer_meta() -> None:
    frame = _frame(
        [
            {
                "is_legacy": False,
                "primary_phy": 3,
                "secondary_phy": 2,
                "adv_sid": 7,
                "adv_tx_power": -4,
                "periodic_interval": 48,
                "data_status": 0,
                "addr_type": 1,
                "lat": 32.8801,
                "lon": -117.234,
                "accuracy": 3.25,
            },
            {},
        ]
    )
    prepared, meta = prepare_payload_v3_parquet_bytes(
        _parquet(frame, V3_URI), route_payload_uri(V3_URI)
    )

    assert prepared.schema == pl.Schema(
        {
            "addr": pl.String,
            "rssi": pl.Int64,
            "scanned_at": pl.Datetime(time_unit="ms", time_zone="UTC"),
            "raw": pl.String,
            "local_name": pl.String,
            "tx_power": pl.Int64,
            "is_connectable": pl.Boolean,
            "lat": pl.Float64,
            "lon": pl.Float64,
            "accuracy": pl.Float32,
            "is_legacy": pl.Boolean,
            "primary_phy": pl.Int64,
            "secondary_phy": pl.Int64,
            "adv_sid": pl.Int64,
            "adv_tx_power": pl.Int64,
            "periodic_interval": pl.Int64,
            "data_status": pl.Int64,
            "addr_type": pl.Int64,
            "blob": pl.String,
            "session_id": pl.String,
        }
    )
    assert prepared.row(0, named=True) | {"scanned_at": None} == {
        "addr": "AA:00:00:03:00:01",
        "rssi": -60,
        "scanned_at": None,
        "raw": RAW_EXT.hex(),
        "local_name": None,
        "tx_power": None,
        "is_connectable": True,
        "lat": 32.8801,
        "lon": -117.234,
        "accuracy": 3.25,
        "is_legacy": False,
        "primary_phy": 3,
        "secondary_phy": 2,
        "adv_sid": 7,
        "adv_tx_power": -4,
        "periodic_interval": 48,
        "data_status": 0,
        "addr_type": 1,
        "blob": V3_URI,
        "session_id": None,
    }
    # The all-null row stays null in every v3 column.
    assert prepared.row(1, named=True)["primary_phy"] is None
    assert meta == {
        "payload_schema": "v3",
        "payload_id": _payload_id(V3_URI),
        "device_model": "Pixel 8",
        "android_version": "15",
        "app_version": "0.2.0",
        "le_extended_advertising_supported": "true",
        "le_coded_phy_supported": "true",
        "le_max_advertising_data_length": "1650",
        "scan_phy": "all_supported",
    }


def test_v3_absent_value_sentinels_become_null() -> None:
    frame = _frame(
        [
            {
                "rssi": 127,
                "adv_sid": 255,
                "adv_tx_power": 127,
                "periodic_interval": 0,
            }
        ]
    )
    prepared, _ = prepare_payload_v3_parquet_bytes(
        _parquet(frame, V3_URI), route_payload_uri(V3_URI)
    )
    row = prepared.row(0, named=True)
    assert [
        row[name] for name in ("rssi", "adv_sid", "adv_tx_power", "periodic_interval")
    ] == [None, None, None, None]


def test_v3_session_footer_is_used_and_kept_in_meta() -> None:
    session_id = "7f0e5b1c-0000-4000-8000-000000000001"
    footer = {**FOOTER, "cluetooth.scan_session_id": session_id}
    prepared, meta = prepare_payload_v3_parquet_bytes(
        _parquet(_frame([{}]), V3_URI, footer=footer), route_payload_uri(V3_URI)
    )
    assert prepared.item(0, "session_id") == session_id
    assert meta["scan_session_id"] == session_id


def test_v3_tolerates_a_footer_without_the_optional_metadata() -> None:
    _, meta = prepare_payload_v3_parquet_bytes(
        _parquet(_frame([{}]), V3_URI, footer={}), route_payload_uri(V3_URI)
    )
    assert meta == {"payload_schema": "v3", "payload_id": _payload_id(V3_URI)}


@pytest.mark.parametrize(
    ("row", "message"),
    [
        ({"primary_phy": 2}, "primary_phy must be 1"),
        ({"primary_phy": 0}, "primary_phy must be 1"),
        ({"secondary_phy": 4}, "secondary_phy must be 0 through 3"),
        ({"secondary_phy": -1}, "secondary_phy must be 0 through 3"),
        ({"adv_sid": 16}, "adv_sid must be 0 through 15"),
        ({"adv_sid": -1}, "adv_sid must be 0 through 15"),
        ({"adv_tx_power": 127 + 1}, "adv_tx_power must be -127 through 126"),
        ({"adv_tx_power": -128}, "adv_tx_power must be -127 through 126"),
        ({"periodic_interval": 65_537}, "periodic_interval must be 1 through 65536"),
        ({"periodic_interval": -1}, "periodic_interval must be 1 through 65536"),
        ({"data_status": 3}, "data_status must be 0 through 2"),
        ({"addr_type": 256}, "addr_type must be 0 through 255"),
        ({"addr_type": -1}, "addr_type must be 0 through 255"),
        # The v2 value rules still apply.
        ({"addr": "aa:00:00:03:00:01"}, "addr must be canonical"),
        ({"lat": 10.0}, "lat and lon must be present together"),
        ({"rssi": 40_000}, "rssi is outside signed-smallint range"),
    ],
)
def test_v3_rejects_invalid_values(row: dict[str, Any], message: str) -> None:
    with pytest.raises(ValueError, match=message):
        prepare_payload_v3_parquet_bytes(
            _parquet(_frame([row]), V3_URI), route_payload_uri(V3_URI)
        )


def test_v3_rejects_required_nulls() -> None:
    frame = _frame([{}]).with_columns(pl.lit(None, dtype=pl.Binary).alias("raw"))
    with pytest.raises(ValueError, match="required payload-v3 column raw"):
        prepare_payload_v3_parquet_bytes(
            _parquet(frame, V3_URI), route_payload_uri(V3_URI)
        )


@pytest.mark.parametrize(
    "mutate",
    [
        lambda f: f.drop("addr_type"),
        lambda f: f.select(*reversed(f.columns)),
        lambda f: f.with_columns(pl.col("adv_sid").cast(pl.Int64)),
        lambda f: f.with_columns(pl.col("is_legacy").cast(pl.Int32)),
        lambda f: f.with_columns(pl.lit(1).alias("extra")),
        # The v2 column set under a v3 footer.
        lambda f: f.select(
            "addr",
            "rssi",
            "scanned_at",
            "raw",
            "local_name",
            "tx_power",
            "is_connectable",
            "lat",
            "lon",
            "accuracy",
        ),
    ],
)
def test_v3_rejects_schema_mismatches(mutate: Any) -> None:
    with pytest.raises(ValueError, match="payload-v3 schema mismatch"):
        prepare_payload_v3_parquet_bytes(
            _parquet(mutate(_frame([{}])), V3_URI), route_payload_uri(V3_URI)
        )


def test_v3_rejects_wrong_footer_schema_and_id() -> None:
    route = route_payload_uri(V3_URI)
    with pytest.raises(ValueError, match="expected cluetooth.payload_schema=v3"):
        prepare_payload_v3_parquet_bytes(
            _parquet(_frame([{}]), V3_URI, schema="v2"), route
        )
    with pytest.raises(ValueError, match="does not match URI"):
        prepare_payload_v3_parquet_bytes(_parquet(_frame([{}]), _uri("b2")), route)


def test_v3_footer_metadata_must_be_unambiguous() -> None:
    route = route_payload_uri(V3_URI)
    base = [
        ("cluetooth.payload_schema", "v3"),
        ("cluetooth.payload_id", route.payload_id),
    ]
    assert read_payload_v3_meta(
        [*base, ("ARROW:schema", "ignored"), ("cluetooth.app_version", "1")], route
    ) == {"payload_schema": "v3", "payload_id": route.payload_id, "app_version": "1"}
    with pytest.raises(ValueError, match="must occur exactly once"):
        read_payload_v3_meta(
            [*base, ("cluetooth.app_version", "1"), ("cluetooth.app_version", "2")],
            route,
        )
    with pytest.raises(ValueError, match="has no value"):
        read_payload_v3_meta([*base, ("cluetooth.app_version", None)], route)


def test_v3_reader_refuses_other_routes() -> None:
    v2_route = route_payload_uri(V3_URI.replace("scans/v3", "scans/v2"))
    with pytest.raises(ValueError, match="payload-v3 reader requires"):
        prepare_payload_v3_parquet_bytes(b"", v2_route)


def _minutes(database_url: str, addr: str) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        """
        select to_char(minute at time zone 'UTC', 'HH24:MI') as minute,
               payload_encode.raw, m.is_legacy, m.primary_phy, m.secondary_phy,
               m.adv_sid, m.data_status, m.addr_type, m.adv_tx_power,
               m.periodic_interval, m.sightings, m.rssi_min, m.rssi_max,
               m.rssi_sum, m.tx_power
        from sighting_minutes m
        cross join lateral (
            select encode(payload_raw(m.payload_id), 'hex') as raw
        ) payload_encode
        where m.addr = $1::macaddr
        order by m.minute, m.is_legacy nulls first, m.primary_phy nulls first,
                 m.secondary_phy nulls first, m.adv_sid nulls first,
                 m.data_status nulls first, m.addr_type nulls first
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [addr]},
    ).to_dicts()


def test_v3_ingest_fills_columns_and_blob_meta(database_url: str) -> None:
    uri = _uri("b3")
    addr = "AA:00:00:03:00:03"
    frame = _frame(
        [
            {
                "addr": addr,
                "is_legacy": False,
                "primary_phy": 3,
                "secondary_phy": 2,
                "adv_sid": 4,
                "adv_tx_power": -8,
                "periodic_interval": 24,
                "data_status": 0,
                "addr_type": 1,
                "scanned_at": datetime(2031, 1, 1, 12, 0, 1, tzinfo=UTC),
                "rssi": -70,
            },
            {
                "addr": addr,
                "is_legacy": False,
                "primary_phy": 3,
                "secondary_phy": 2,
                "adv_sid": 4,
                "adv_tx_power": -2,
                "periodic_interval": 48,
                "data_status": 0,
                "addr_type": 1,
                "scanned_at": datetime(2031, 1, 1, 12, 0, 30, tzinfo=UTC),
                "rssi": -50,
            },
        ]
    )
    assert _ingest(database_url, uri, _parquet(frame, uri)) == 2

    rows = _minutes(database_url, addr)
    assert rows == [
        {
            "minute": "12:00",
            "raw": RAW_EXT.hex(),
            "is_legacy": False,
            "primary_phy": 3,
            "secondary_phy": 2,
            "adv_sid": 4,
            "data_status": 0,
            "addr_type": 1,
            # Measurements merge as the maximum.
            "adv_tx_power": -2,
            "periodic_interval": 48,
            "sightings": 2,
            "rssi_min": -70,
            "rssi_max": -50,
            "rssi_sum": -120,
            "tx_power": None,
        }
    ]
    ledger = pl.read_database_uri(
        "select success, app_version, meta::text as meta from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).row(0, named=True)
    assert ledger["success"] is True
    assert ledger["app_version"] == "0.2.0"
    meta = json.loads(ledger["meta"])
    assert meta["payload_id"] == _payload_id(uri)
    assert meta["le_max_advertising_data_length"] == "1650"
    assert meta["device_model"] == "Pixel 8"


def test_v3_pdu_fields_are_part_of_the_minute_key(database_url: str) -> None:
    """The same address and bytes seen as different kinds of PDU stay apart."""
    uri = _uri("b4")
    addr = "AA:00:00:03:00:04"
    same_minute = datetime(2031, 1, 1, 12, 0, 10, tzinfo=UTC)
    legacy_pdu = {"is_legacy": True, "primary_phy": 1, "secondary_phy": 0}
    ext_2m = {"is_legacy": False, "primary_phy": 1, "secondary_phy": 2, "adv_sid": 1}
    ext_coded = {"is_legacy": False, "primary_phy": 3, "secondary_phy": 3, "adv_sid": 1}
    frame = _frame(
        [
            {"addr": addr, "scanned_at": same_minute, **legacy_pdu},
            {"addr": addr, "scanned_at": same_minute, **legacy_pdu},
            {"addr": addr, "scanned_at": same_minute, **ext_2m},
            {"addr": addr, "scanned_at": same_minute, **ext_coded},
            {"addr": addr, "scanned_at": same_minute, **ext_coded, "adv_sid": 2},
            {"addr": addr, "scanned_at": same_minute, **ext_coded, "data_status": 1},
            # Producers that do not report v3 fields leave them all null.
            {"addr": addr, "scanned_at": same_minute},
        ]
    )
    assert _ingest(database_url, uri, _parquet(frame, uri)) == 7

    rows = _minutes(database_url, addr)
    assert len(rows) == 6
    by_key = {
        (
            r["is_legacy"],
            r["primary_phy"],
            r["secondary_phy"],
            r["adv_sid"],
            r["data_status"],
        ): r["sightings"]
        for r in rows
    }
    assert by_key == {
        (None, None, None, None, None): 1,
        (True, 1, 0, None, None): 2,
        (False, 1, 2, 1, None): 1,
        (False, 3, 3, 1, None): 1,
        (False, 3, 3, 2, None): 1,
        (False, 3, 3, 1, 1): 1,
    }


def test_v3_minute_split_across_blobs_merges_exactly_and_rebuilds(
    database_url: str,
) -> None:
    addr = "AA:00:00:03:00:05"
    first_uri, second_uri = _uri("b5"), _uri("b6")
    pdu = {
        "addr": addr,
        "is_legacy": False,
        "primary_phy": 1,
        "secondary_phy": 2,
        "adv_sid": 3,
        "addr_type": 1,
    }
    first = _frame(
        [
            {
                **pdu,
                "scanned_at": datetime(2031, 2, 1, 12, 5, 1, tzinfo=UTC),
                "rssi": -80,
                "adv_tx_power": -10,
            }
        ]
    )
    second = _frame(
        [
            {
                **pdu,
                "scanned_at": datetime(2031, 2, 1, 12, 5, 50, tzinfo=UTC),
                "rssi": -40,
                "adv_tx_power": 3,
                "periodic_interval": 100,
            },
            # Another kind of PDU in the same minute: its own row.
            {
                **pdu,
                "adv_sid": 9,
                "scanned_at": datetime(2031, 2, 1, 12, 5, 50, tzinfo=UTC),
            },
        ]
    )
    first_bytes, second_bytes = (
        _parquet(first, first_uri),
        _parquet(second, second_uri),
    )
    assert _ingest(database_url, first_uri, first_bytes) == 1
    assert _ingest(database_url, second_uri, second_bytes) == 2

    expected = _minutes(database_url, addr)
    assert len(expected) == 2
    merged = next(row for row in expected if row["adv_sid"] == 3)
    assert (merged["sightings"], merged["rssi_min"], merged["rssi_max"]) == (
        2,
        -80,
        -40,
    )
    assert (merged["adv_tx_power"], merged["periodic_interval"]) == (3, 100)

    class _Storage:
        def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
            return []

        def read_blob_bytes(self, blob_uri: str) -> bytes:
            return _encrypt(
                {first_uri: first_bytes, second_uri: second_bytes}[blob_uri]
            )

    result = rebuild_minutes(
        database_url,
        _Storage(),
        bytes(PRIVATE_KEY),
        since=datetime(2031, 2, 1, 12, 0, tzinfo=UTC),
        until=datetime(2031, 2, 1, 13, 0, tzinfo=UTC),
    )
    assert result.sightings >= 3
    assert _minutes(database_url, addr) == expected


def test_v3_and_v2_rows_coexist_with_null_pdu_fields(database_url: str) -> None:
    """A v2-style row (all v3 key fields null) and a v3 row do not merge."""
    uri = _uri("b7")
    addr = "AA:00:00:03:00:07"
    when = datetime(2031, 1, 1, 12, 7, 0, tzinfo=UTC)
    frame = _frame(
        [
            {"addr": addr, "scanned_at": when},
            {"addr": addr, "scanned_at": when, "is_legacy": True},
        ]
    )
    assert _ingest(database_url, uri, _parquet(frame, uri)) == 2
    rows = _minutes(database_url, addr)
    assert [(r["is_legacy"], r["sightings"]) for r in rows] == [(None, 1), (True, 1)]


def test_valid_empty_v3_file_is_successful_and_keeps_its_meta(
    database_url: str,
) -> None:
    uri = _uri("b8")
    assert _ingest(database_url, uri, _parquet(_frame([]), uri)) == 0
    ledger = pl.read_database_uri(
        "select success, app_version, meta ->> 'le_coded_phy_supported' as coded "
        "from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).to_dicts()
    assert ledger == [{"success": True, "app_version": "0.2.0", "coded": "true"}]


def test_invalid_v3_file_is_recorded_as_failed(database_url: str) -> None:
    uri = _uri("b9")
    bad = _parquet(_frame([{"adv_sid": 99}]), uri)
    assert _ingest(database_url, uri, bad) is None
    ledger = pl.read_database_uri(
        "select success, error, attempts from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri]},
    ).row(0, named=True)
    assert ledger["success"] is False
    assert "adv_sid must be 0 through 15" in ledger["error"]
    assert ledger["attempts"] == 1
