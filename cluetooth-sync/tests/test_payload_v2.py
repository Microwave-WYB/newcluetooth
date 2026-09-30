from __future__ import annotations

import io
from pathlib import Path

import compression.zstd as zstd
import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox
from thrift.Thrift import TType
from thrift.protocol import TCompactProtocol
from thrift.transport import TTransport

from cluetooth_sync.pipeline import (
    PayloadRouteKind,
    UnsupportedPayloadPathError,
    UnsupportedPayloadSchemaError,
    prepare_payload_v2_parquet_bytes,
    route_payload_uri,
)
from cluetooth_sync.parquet_footer import read_parquet_key_value_occurrences
from cluetooth_sync.pipeline.orchestrate import BlobBytes, _ingest_blob
from cluetooth_sync.pipeline.payload_v2 import _required_metadata_value
from cluetooth_sync.pipeline import db

PAYLOAD_ID = "0195c920-7c00-7abc-8def-0123456789ab"
V2_URI = f"gs://test-bucket/scans/v2/2025/03/24/{PAYLOAD_ID}.parquet.encrypted"
FIXTURE = (
    Path(__file__).parent
    / "fixtures/payload-v2/scans/v2/2025/03/24"
    / f"{PAYLOAD_ID}.parquet"
)
LEGACY_URI = (
    "gs://test-bucket/scans/"
    "2026-04-10T19-37-46.210Z_device_0.0.4-debug.jsonl.zst.encrypted"
)


def test_rust_fixture_exact_contract_and_raw_round_trip() -> None:
    route = route_payload_uri(V2_URI)
    scans = prepare_payload_v2_parquet_bytes(FIXTURE.read_bytes(), route)

    assert route.kind is PayloadRouteKind.PAYLOAD_V2_PARQUET
    assert route.payload_id == PAYLOAD_ID
    assert route.utc_date == "2025-03-24"
    assert scans.schema == pl.Schema(
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
            "blob": pl.String,
        }
    )
    assert scans.select(
        "addr", "raw", "local_name", "lat", "lon", "accuracy"
    ).to_dicts() == [
        {
            "addr": "AA:BB:CC:DD:EE:FF",
            "raw": "02010605ff0080fe",
            "local_name": "sensor",
            "lat": 32.8801,
            "lon": -117.234,
            "accuracy": 3.25,
        },
        {
            "addr": "00:11:22:33:44:55",
            "raw": "00ff1000",
            "local_name": None,
            "lat": None,
            "lon": None,
            "accuracy": None,
        },
    ]
    metadata = pl.read_parquet_metadata(FIXTURE)
    assert metadata["cluetooth.payload_schema"] == "v2"
    assert metadata["cluetooth.payload_id"] == PAYLOAD_ID


def _parquet_with_metadata_occurrences(
    occurrences: list[tuple[str, str | None]],
) -> bytes:
    transport = TTransport.TMemoryBuffer()
    protocol = TCompactProtocol.TCompactProtocol(transport)
    protocol.writeStructBegin("FileMetaData")
    protocol.writeFieldBegin("key_value_metadata", TType.LIST, 5)
    protocol.writeListBegin(TType.STRUCT, len(occurrences))
    for key, value in occurrences:
        protocol.writeStructBegin("KeyValue")
        protocol.writeFieldBegin("key", TType.STRING, 1)
        protocol.writeString(key)
        protocol.writeFieldEnd()
        if value is not None:
            protocol.writeFieldBegin("value", TType.STRING, 2)
            protocol.writeString(value)
            protocol.writeFieldEnd()
        protocol.writeFieldStop()
        protocol.writeStructEnd()
    protocol.writeListEnd()
    protocol.writeFieldEnd()
    protocol.writeFieldStop()
    protocol.writeStructEnd()
    footer = transport.getvalue()
    return b"PAR1" + footer + len(footer).to_bytes(4, "little") + b"PAR1"


def test_footer_reader_preserves_required_metadata_occurrences() -> None:
    occurrences = read_parquet_key_value_occurrences(FIXTURE.read_bytes())
    assert ("cluetooth.payload_schema", "v2") in occurrences
    assert ("cluetooth.payload_id", PAYLOAD_ID) in occurrences


@pytest.mark.parametrize(
    ("key", "valid", "attacker"),
    [
        ("cluetooth.payload_schema", "v2", "attacker"),
        ("cluetooth.payload_id", PAYLOAD_ID, "0195c920-7c00-7abc-8def-0123456789ac"),
    ],
)
@pytest.mark.parametrize("valid_first", [True, False])
def test_duplicate_required_footer_keys_are_rejected_in_both_orders(
    key: str, valid: str, attacker: str, valid_first: bool
) -> None:
    duplicates: list[tuple[str, str | None]] = [(key, valid), (key, attacker)]
    if not valid_first:
        duplicates.reverse()
    other: list[tuple[str, str | None]] = (
        [("cluetooth.payload_id", PAYLOAD_ID)]
        if key == "cluetooth.payload_schema"
        else [("cluetooth.payload_schema", "v2")]
    )
    payload = _parquet_with_metadata_occurrences(other + duplicates)
    with pytest.raises(ValueError, match="must occur exactly once"):
        prepare_payload_v2_parquet_bytes(payload, route_payload_uri(V2_URI))


@pytest.mark.parametrize("occurrences", [[], [("cluetooth.payload_schema", None)]])
def test_missing_or_valueless_required_footer_key_is_rejected(
    occurrences: list[tuple[str, str | None]],
) -> None:
    with pytest.raises(ValueError):
        _required_metadata_value(occurrences, "cluetooth.payload_schema")


def test_malformed_compact_footer_is_rejected_before_polars_read() -> None:
    malformed = b"PAR1" + b"not compact thrift" + (18).to_bytes(4, "little") + b"PAR1"
    with pytest.raises(ValueError, match="malformed Parquet"):
        prepare_payload_v2_parquet_bytes(malformed, route_payload_uri(V2_URI))


def test_v2_fixture_has_no_outer_zstd_layer() -> None:
    with pytest.raises(zstd.ZstdError):
        zstd.decompress(FIXTURE.read_bytes())
    assert (
        prepare_payload_v2_parquet_bytes(
            FIXTURE.read_bytes(), route_payload_uri(V2_URI)
        ).height
        == 2
    )


@pytest.mark.parametrize(
    ("uri", "error_type"),
    [
        (
            f"gs://bucket/scans/schema=v2/2025/03/24/{PAYLOAD_ID}.parquet.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        (
            f"gs://bucket/scans/v1/2025/03/24/{PAYLOAD_ID}.parquet.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        (
            f"gs://bucket/scans/v3/2025/03/24/{PAYLOAD_ID}.parquet.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        (
            f"gs://bucket/scans/v2/2025/03/23/{PAYLOAD_ID}.parquet.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            f"gs://bucket/scans/v2/2025/03/24/{PAYLOAD_ID.upper()}.parquet.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/v2/2025/03/24/"
            "550e8400-e29b-41d4-a716-446655440000.parquet.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            f"gs://bucket/scans/v2/../../etc/{PAYLOAD_ID}.parquet.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/schema=vNext/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        (
            "gs://bucket/scans/schema=next/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        (
            "gs://bucket/scans/nested/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/../device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/./device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket//scans/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/v2/device_0.0.4.jsonl.zst.encrypted",
            UnsupportedPayloadPathError,
        ),
        (
            "gs://bucket/scans/device_0.0.5-debug.jsonl.zst.encrypted",
            UnsupportedPayloadSchemaError,
        ),
        ("gs://bucket/scans/random.jsonl.zst.encrypted", UnsupportedPayloadSchemaError),
        ("https://bucket/scans/file", UnsupportedPayloadPathError),
    ],
)
def test_route_error_matrix(uri: str, error_type: type[ValueError]) -> None:
    with pytest.raises(error_type):
        route_payload_uri(uri)


def test_legacy_route_remains_limited_to_0_0_1_through_0_0_4() -> None:
    route = route_payload_uri(LEGACY_URI)
    assert route.kind is PayloadRouteKind.LEGACY_JSONL_ZSTD


def test_footer_mismatch_and_required_null_are_rejected() -> None:
    frame = pl.read_parquet(FIXTURE)
    output = io.BytesIO()
    frame.write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v2",
            "cluetooth.payload_id": "0195c920-7c00-7abc-8def-0123456789ac",
        },
    )
    with pytest.raises(ValueError, match="does not match URI"):
        prepare_payload_v2_parquet_bytes(output.getvalue(), route_payload_uri(V2_URI))

    null_frame = frame.with_columns(
        pl.when(pl.int_range(pl.len()) == 0)
        .then(None)
        .otherwise(pl.col("addr"))
        .alias("addr")
    )
    output = io.BytesIO()
    null_frame.write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v2",
            "cluetooth.payload_id": PAYLOAD_ID,
        },
    )
    with pytest.raises(ValueError, match="required payload-v2 column addr"):
        prepare_payload_v2_parquet_bytes(output.getvalue(), route_payload_uri(V2_URI))

    reordered = frame.select("rssi", "addr", *frame.columns[2:])
    output = io.BytesIO()
    reordered.write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v2",
            "cluetooth.payload_id": PAYLOAD_ID,
        },
    )
    with pytest.raises(ValueError, match="payload-v2 schema mismatch"):
        prepare_payload_v2_parquet_bytes(output.getvalue(), route_payload_uri(V2_URI))


def test_v2_rejects_noncanonical_or_out_of_range_values() -> None:
    frame = pl.read_parquet(FIXTURE)
    invalid_frames = {
        "addr": frame.with_columns(pl.lit("aa:bb:cc:dd:ee:ff").alias("addr")),
        "smallint": frame.with_columns(pl.lit(40_000, dtype=pl.Int32).alias("rssi")),
        "present together": frame.with_columns(
            pl.lit(None, dtype=pl.Float64).alias("lon")
        ),
        "lat must": frame.with_columns(
            pl.when(pl.int_range(pl.len()) == 0)
            .then(float("nan"))
            .otherwise(pl.col("lat"))
            .alias("lat")
        ),
        "lon must": frame.with_columns(
            pl.when(pl.int_range(pl.len()) == 0)
            .then(float("inf"))
            .otherwise(pl.col("lon"))
            .alias("lon")
        ),
        "accuracy requires": frame.with_columns(
            pl.lit(None, dtype=pl.Float64).alias("lat"),
            pl.lit(None, dtype=pl.Float64).alias("lon"),
            pl.lit(1.0, dtype=pl.Float32).alias("accuracy"),
        ),
        "accuracy must": frame.with_columns(
            pl.when(pl.int_range(pl.len()) == 0)
            .then(pl.lit(-1.0, dtype=pl.Float32))
            .otherwise(pl.col("accuracy"))
            .alias("accuracy")
        ),
    }
    route = route_payload_uri(V2_URI)
    for expected, invalid in invalid_frames.items():
        output = io.BytesIO()
        invalid.write_parquet(
            output,
            metadata={
                "cluetooth.payload_schema": "v2",
                "cluetooth.payload_id": PAYLOAD_ID,
            },
        )
        with pytest.raises(ValueError, match=expected):
            prepare_payload_v2_parquet_bytes(output.getvalue(), route)


def test_float64_accuracy_is_not_silently_accepted_as_v2() -> None:
    frame = pl.read_parquet(FIXTURE).with_columns(pl.col("accuracy").cast(pl.Float64))
    output = io.BytesIO()
    frame.write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v2",
            "cluetooth.payload_id": PAYLOAD_ID,
        },
    )
    with pytest.raises(ValueError, match="schema mismatch"):
        prepare_payload_v2_parquet_bytes(output.getvalue(), route_payload_uri(V2_URI))


def test_encrypted_v2_payload_runs_through_decrypt_validation_and_database_contract(
    database_url: str,
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    ciphertext = SealedBox(private_key.public_key).encrypt(FIXTURE.read_bytes())

    assert _ingest_blob(database_url, bytes(private_key), BlobBytes(V2_URI, ciphertext))

    inserted = pl.read_database_uri(
        """
        select addr::text as addr, encode(raw, 'hex') as raw,
               pg_typeof(accuracy)::text as accuracy_type, accuracy
        from scans
        where blob = $1
        order by addr::text
        """,
        database_url,
        engine="adbc",
        execute_options={"parameters": [V2_URI]},
    )
    assert inserted.to_dicts() == [
        {
            "addr": "00:11:22:33:44:55",
            "raw": "00ff1000",
            "accuracy_type": "real",
            "accuracy": None,
        },
        {
            "addr": "aa:bb:cc:dd:ee:ff",
            "raw": "02010605ff0080fe",
            "accuracy_type": "real",
            "accuracy": 3.25,
        },
    ]
    ledger = pl.read_database_uri(
        "select success from blobs where uri = $1",
        database_url,
        engine="adbc",
        execute_options={"parameters": [V2_URI]},
    )
    assert ledger.to_dicts() == [{"success": True}]


def test_database_coordinate_constraints_reject_nan_infinity_and_unpaired_values(
    database_url: str,
) -> None:
    invalid_values = [
        ("NULL", "0", "NULL"),
        ("'NaN'::float8", "0", "NULL"),
        ("0", "'Infinity'::float8", "NULL"),
        ("0", "0", "'NaN'::float4"),
        ("0", "0", "'Infinity'::float4"),
        ("0", "0", "-1::float4"),
        ("NULL", "NULL", "1::float4"),
    ]
    for lat, lon, accuracy in invalid_values:
        with pytest.raises(Exception):
            with db.session(database_url) as database:
                database.cursor.execute(
                    f"insert into scans (addr, scanned_at, raw, lat, lon, accuracy) "
                    f"values ('AA:BB:CC:DD:EE:FF', now(), '\\x00', {lat}, {lon}, {accuracy})"
                )


def test_valid_empty_exact_schema_payload_is_marked_successful(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    output = io.BytesIO()
    pl.read_parquet(FIXTURE).clear().write_parquet(
        output,
        metadata={
            "cluetooth.payload_schema": "v2",
            "cluetooth.payload_id": PAYLOAD_ID,
        },
    )
    succeeded: list[str] = []
    inserted: list[str] = []
    failed: list[str] = []
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.decrypt_blob_bytes",
        lambda encrypted, private_key: encrypted,
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.mark_blob_succeeded_empty",
        lambda database_url, uri: succeeded.append(uri),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.insert_prepared_scans",
        lambda database_url, scans, mark_failure: inserted.append("unexpected"),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.mark_blob_failed",
        lambda database_url, uri, exc: failed.append(uri),
    )

    assert _ingest_blob("db", b"key", BlobBytes(V2_URI, output.getvalue()))
    assert succeeded == [V2_URI]
    assert inserted == []
    assert failed == []


def test_mixed_legacy_and_v2_ingest_routes_before_decoding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared: list[tuple[str, bytes]] = []
    inserted: list[tuple[str, int]] = []
    failed: list[str] = []

    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.decrypt_blob_bytes",
        lambda encrypted, private_key: encrypted,
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.prepare_scan_jsonl_bytes",
        lambda data, uri: prepared.append((uri, data)) or pl.DataFrame({"blob": [uri]}),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.prepare_payload_v2_parquet_bytes",
        lambda data, route: (
            prepared.append((route.uri, data)) or pl.DataFrame({"blob": [route.uri]})
        ),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.insert_prepared_scans",
        lambda database_url, scans, mark_failure: (
            inserted.append((scans.item(0, "blob"), scans.height)) or scans.height
        ),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.mark_blob_failed",
        lambda database_url, uri, exc: failed.append(uri),
    )

    legacy_plaintext = b"legacy-jsonl"
    assert _ingest_blob(
        "db", b"key", BlobBytes(LEGACY_URI, zstd.compress(legacy_plaintext))
    )
    assert _ingest_blob("db", b"key", BlobBytes(V2_URI, b"direct-parquet"))

    assert prepared == [(LEGACY_URI, legacy_plaintext), (V2_URI, b"direct-parquet")]
    assert inserted == [(LEGACY_URI, 1), (V2_URI, 1)]
    assert failed == []


def test_whole_blob_failure_is_bookkept_once_and_retryable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    failures: list[tuple[str, str]] = []
    attempts = 0

    def decrypt(encrypted: bytes, private_key: bytes) -> bytes:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise ValueError("bad ciphertext")
        return encrypted

    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.decrypt_blob_bytes", decrypt
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.prepare_payload_v2_parquet_bytes",
        lambda data, route: pl.DataFrame({"blob": [route.uri]}),
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.insert_prepared_scans",
        lambda database_url, scans, mark_failure: scans.height,
    )
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.mark_blob_failed",
        lambda database_url, uri, exc: failures.append((uri, str(exc))),
    )

    blob = BlobBytes(V2_URI, b"parquet")
    assert not _ingest_blob("db", b"key", blob)
    assert _ingest_blob("db", b"key", blob)
    assert failures == [(V2_URI, "bad ciphertext")]
