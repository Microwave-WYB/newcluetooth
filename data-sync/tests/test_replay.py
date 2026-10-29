from __future__ import annotations

import base64
import compression.zstd as zstd
import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import polars as pl
import pytest
from nacl.public import PrivateKey, SealedBox
from typer.testing import CliRunner

import cluetooth_sync.cli as cli
from cluetooth_sync.pipeline import (
    insert_prepared_scans,
    prepare_scan_jsonl_bytes,
    rebuild_minutes,
    replay_sightings,
)


RAW = "02010605094e616d6505ff4c000102"
ADDR_A = "AA:00:00:00:01:01"
ADDR_B = "AA:00:00:00:01:02"
FIRST_URI = (
    "gs://test-bucket/scans/2026-07-01T00-00-00Z_device_0.0.3.jsonl.zst.encrypted"
)
SECOND_URI = (
    "gs://test-bucket/scans/2026-07-01T00-05-00Z_device_0.0.3.jsonl.zst.encrypted"
)
THIRD_URI = (
    "gs://test-bucket/scans/2026-07-02T00-00-00Z_device_0.0.3.jsonl.zst.encrypted"
)
FOURTH_URI = (
    "gs://test-bucket/scans/2026-07-02T00-05-00Z_device_0.0.3.jsonl.zst.encrypted"
)
SINCE = datetime(2026, 7, 1, 0, 0, tzinfo=UTC)
UNTIL = datetime(2026, 7, 1, 1, 0, tzinfo=UTC)
LATER_SINCE = datetime(2026, 7, 2, 0, 0, tzinfo=UTC)
LATER_UNTIL = datetime(2026, 7, 2, 1, 0, tzinfo=UTC)


class _StorageClient:
    def __init__(self, blobs: dict[str, bytes]) -> None:
        self.blobs = blobs
        self.read_uris: list[str] = []

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        uri_prefix = f"gs://{bucket_name}/{prefix}"
        return [uri for uri in self.blobs if uri.startswith(uri_prefix)]

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        self.read_uris.append(blob_uri)
        return self.blobs[blob_uri]


def _legacy_jsonl(addr: str, rows: list[tuple[str, str, int]]) -> bytes:
    """Legacy JSONL: base64 `raw`, `mac`/`timestamp` field names."""
    return (
        "".join(
            json.dumps(
                {
                    "mac": addr,
                    "rssi": rssi,
                    "timestamp": scanned_at,
                    "lat": None,
                    "lon": None,
                    "accuracy": None,
                    "raw": base64.b64encode(bytes.fromhex(RAW)).decode(),
                }
            )
            + "\n"
            for scanned_at, raw, rssi in rows
        )
    ).encode()


def _encrypt(plaintext: bytes, private_key: PrivateKey) -> bytes:
    return SealedBox(private_key.public_key).encrypt(zstd.compress(plaintext))


def _samples(day: str) -> tuple[tuple[str, str, int], tuple[str, str, int]]:
    return (
        (f"{day}T00:00:00.000Z", RAW, -50),
        (f"{day}T00:00:10.000Z", RAW, -60),
    )


FIRST_DAY_SAMPLES = _samples("2026-07-01")
SECOND_DAY_SAMPLES = _samples("2026-07-02")


def _minute_rows(database_url: str, addr: str) -> list[dict[str, Any]]:
    return pl.read_database_uri(
        f"""
        select
          m.id,
          m.minute,
          m.addr::text as addr,
          m.sightings,
          m.first_seen,
          m.last_seen,
          m.rssi_min,
          m.rssi_max,
          m.rssi_sum,
          m.rssi_count,
          m.best_rssi,
          m.best_seen,
          m.blob_counts::text as blob_counts,
          m.blob_ids
        from sighting_minutes m
        where m.addr = cast('{addr}' as macaddr)
          and m.minute >= timestamp '2026-07-01 00:00:00+00'
          and m.minute < timestamp '2026-07-03 00:00:00+00'
        order by m.minute
        """,
        database_url,
        engine="adbc",
    ).to_dicts()


def _payload_sightings(database_url: str) -> int:
    return int(
        pl.read_database_uri(
            """
            select p.sightings
            from payloads p
            where p.sha256 = sha256(decode($1, 'hex'))
            """,
            database_url,
            engine="adbc",
            execute_options={"parameters": [RAW]},
        ).item()
    )


def test_replay_decrypts_and_parses_an_archived_blob() -> None:
    private_key = PrivateKey(bytes(range(32)))
    plaintext = _legacy_jsonl(ADDR_A, [FIRST_DAY_SAMPLES[0]])
    storage_client = _StorageClient({FIRST_URI: _encrypt(plaintext, private_key)})

    frames = list(replay_sightings(storage_client, bytes(private_key), [FIRST_URI]))

    assert len(frames) == 1
    assert frames[0].select("addr", "raw", "blob").to_dicts() == [
        {"addr": ADDR_A, "raw": RAW, "blob": FIRST_URI}
    ]

    outside = list(
        replay_sightings(
            storage_client,
            bytes(private_key),
            [FIRST_URI],
            since=datetime(2027, 1, 1, tzinfo=UTC),
        )
    )
    assert outside[0].is_empty()


def test_replay_command_writes_ndjson(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    key_path = tmp_path / "key"
    key_path.write_bytes(base64.b64encode(bytes(private_key)))
    storage_client = _StorageClient(
        {
            FIRST_URI: _encrypt(
                _legacy_jsonl(ADDR_A, [FIRST_DAY_SAMPLES[0]]), private_key
            )
        }
    )
    monkeypatch.setattr(cli, "_build_storage_client", lambda *args: storage_client)

    result = CliRunner().invoke(
        cli.app,
        [
            "replay",
            "--database-url",
            "postgres://unused",
            "--private-key",
            str(key_path),
            "--blob",
            FIRST_URI,
            "--format",
            "ndjson",
        ],
    )

    assert result.exit_code == 0, result.output
    lines = [
        json.loads(line) for line in result.output.splitlines() if line.startswith("{")
    ]
    assert len(lines) == 1
    assert lines[0]["raw"] == RAW
    assert lines[0]["addr"] == ADDR_A
    assert lines[0]["blob"] == FIRST_URI
    assert "replayed 1 sightings from 1 objects" in result.output
    assert storage_client.read_uris == [FIRST_URI]


def test_rebuild_reproduces_rows_and_keeps_ids(database_url: str) -> None:
    private_key = PrivateKey(bytes(range(32)))
    first_plaintext = _legacy_jsonl(ADDR_A, [FIRST_DAY_SAMPLES[0]])
    second_plaintext = _legacy_jsonl(ADDR_A, [FIRST_DAY_SAMPLES[1]])
    storage_client = _StorageClient(
        {
            FIRST_URI: _encrypt(first_plaintext, private_key),
            SECOND_URI: _encrypt(second_plaintext, private_key),
        }
    )
    assert (
        insert_prepared_scans(
            database_url, prepare_scan_jsonl_bytes(first_plaintext, FIRST_URI)
        )
        == 1
    )
    assert (
        insert_prepared_scans(
            database_url, prepare_scan_jsonl_bytes(second_plaintext, SECOND_URI)
        )
        == 1
    )

    before = _minute_rows(database_url, ADDR_A)
    assert [row["sightings"] for row in before] == [2]
    stats_before = _payload_sightings(database_url)

    result = rebuild_minutes(
        database_url,
        storage_client,
        bytes(private_key),
        since=SINCE,
        until=UNTIL,
    )

    assert result.blobs == 2
    assert result.sightings == 2
    assert sorted(storage_client.read_uris) == sorted([FIRST_URI, SECOND_URI])
    assert _minute_rows(database_url, ADDR_A) == before
    # Rebuild regenerates minutes only; the payload stats already count them.
    assert _payload_sightings(database_url) == stats_before


def test_rebuild_follows_the_archive(database_url: str) -> None:
    """The objects are the source of truth: a rebuild reproduces what they hold."""
    private_key = PrivateKey(bytes(range(32)))
    third_plaintext = _legacy_jsonl(ADDR_B, [SECOND_DAY_SAMPLES[0]])
    fourth_plaintext = _legacy_jsonl(ADDR_B, [SECOND_DAY_SAMPLES[1]])
    storage_client = _StorageClient(
        {
            THIRD_URI: _encrypt(third_plaintext, private_key),
            FOURTH_URI: _encrypt(fourth_plaintext, private_key),
        }
    )
    for uri, plaintext in (
        (THIRD_URI, third_plaintext),
        (FOURTH_URI, fourth_plaintext),
    ):
        assert (
            insert_prepared_scans(
                database_url, prepare_scan_jsonl_bytes(plaintext, uri)
            )
            == 1
        )

    before = _minute_rows(database_url, ADDR_B)
    assert before[0]["sightings"] == 2
    assert before[0]["rssi_min"] == -60
    assert before[0]["rssi_max"] == -50

    # The second object now holds the weaker sample twice.
    storage_client.blobs[FOURTH_URI] = _encrypt(
        _legacy_jsonl(ADDR_B, [SECOND_DAY_SAMPLES[0], SECOND_DAY_SAMPLES[0]]),
        private_key,
    )
    result = rebuild_minutes(
        database_url,
        storage_client,
        bytes(private_key),
        since=LATER_SINCE,
        until=LATER_UNTIL,
    )

    assert result.sightings == 3
    rebuilt = _minute_rows(database_url, ADDR_B)
    assert len(rebuilt) == 1
    assert rebuilt[0]["id"] == before[0]["id"]
    assert rebuilt[0]["sightings"] == 3
    assert rebuilt[0]["rssi_min"] == -50
    assert rebuilt[0]["rssi_max"] == -50
    # No located sample: the minute keeps no best sample and no location.
    assert rebuilt[0]["best_rssi"] is None
    assert rebuilt[0]["best_seen"] is None
    assert rebuilt[0]["first_seen"] == datetime(2026, 7, 2, 0, 0, tzinfo=UTC)
    assert rebuilt[0]["last_seen"] == datetime(2026, 7, 2, 0, 0, tzinfo=UTC)


def test_rebuild_of_an_empty_range_touches_nothing(database_url: str) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _StorageClient({})

    result = rebuild_minutes(
        database_url,
        storage_client,
        bytes(private_key),
        since=datetime(2030, 1, 1, tzinfo=UTC),
        until=datetime(2030, 1, 2, tzinfo=UTC),
    )

    assert result.blobs == 0
    assert result.sightings == 0
    assert storage_client.read_uris == []
