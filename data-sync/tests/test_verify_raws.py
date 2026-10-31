from __future__ import annotations

import base64
import compression.zstd as zstd
import json
from pathlib import Path

import pytest
from nacl.public import PrivateKey, SealedBox
from typer.testing import CliRunner

import cluetooth_sync.cli as cli
import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline import run_verify_raws
from cluetooth_sync.pipeline.verify_raws import compare_blob


RAW = "02010605094e616d6505ff4c000102"
BLOB_URI = (
    "gs://test-bucket/scans/2026-08-01T00-00-00Z_device_0.0.4.jsonl.zst.encrypted"
)
REJECTED_URI = "gs://test-bucket/scans/schema=v2/2026-08-01T00-00-00Z_device_0.0.4.jsonl.zst.encrypted"


class _StorageClient:
    def __init__(self, blobs: dict[str, bytes]) -> None:
        self.blobs = blobs
        self.read_uris: list[str] = []

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        return list(self.blobs)

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        self.read_uris.append(blob_uri)
        return self.blobs[blob_uri]


def _jsonl(rows: list[tuple[str, str, str, int]]) -> bytes:
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
                    "raw": base64.b64encode(bytes.fromhex(raw)).decode(),
                }
            )
            + "\n"
            for addr, scanned_at, raw, rssi in rows
        )
    ).encode()


ROWS = [
    ("AA:00:00:00:02:01", "2026-08-01T00:00:00.000Z", RAW, -50),
    ("AA:00:00:00:02:02", "2026-08-01T00:00:10.000Z", RAW, -60),
]


def _encrypt(plaintext: bytes, private_key: PrivateKey) -> bytes:
    return SealedBox(private_key.public_key).encrypt(zstd.compress(plaintext))


def _blob_row(database_url: str, uri: str, success: bool = True) -> None:
    with db.session(database_url) as session:
        session.cursor.execute(
            "insert into blobs (uri, success) values ($1, $2) "
            "on conflict (uri) do update set success = excluded.success",
            [uri, success],
        )


def _old_scan(
    database_url: str, *, uri: str, addr: str, scanned_at: str, raw: str, rssi: int
) -> None:
    """One pre-restructure per-sighting row for the sampled blob."""
    with db.session(database_url) as session:
        session.cursor.execute(
            """
            insert into scans_old (blob, addr, scanned_at, raw, rssi)
            values ($1, cast($2 as macaddr), cast($3 as timestamptz),
                    decode($4, 'hex'), $5::smallint)
            """,
            [uri, addr, scanned_at, raw, rssi],
        )


def _prepare(database_url: str, private_key: PrivateKey) -> _StorageClient:
    _blob_row(database_url, BLOB_URI)
    for addr, scanned_at, raw, rssi in ROWS:
        _old_scan(
            database_url,
            uri=BLOB_URI,
            addr=addr,
            scanned_at=scanned_at,
            raw=raw,
            rssi=rssi,
        )
    return _StorageClient({BLOB_URI: _encrypt(_jsonl(ROWS), private_key)})


def test_verify_raws_matches_the_archive_against_scans_old(
    clean_database_url: str,
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _prepare(clean_database_url, private_key)

    result = run_verify_raws(
        clean_database_url, storage_client, bytes(private_key), sample=5
    )

    assert result.ok
    assert result.mismatched == 0
    assert result.skipped == ()
    assert len(result.comparisons) == 1
    comparison = result.comparisons[0]
    assert comparison.uri == BLOB_URI
    assert comparison.archived == 2
    assert comparison.old == 2
    assert comparison.mismatched == 0
    assert storage_client.read_uris == [BLOB_URI]


def test_verify_raws_reports_a_changed_raw_byte(clean_database_url: str) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _prepare(clean_database_url, private_key)
    # The archive keeps the advertisement as it was observed; a re-encoded raw
    # is a different sighting identity.
    storage_client.blobs[BLOB_URI] = _encrypt(
        _jsonl(
            [
                ROWS[0],
                (ROWS[1][0], ROWS[1][1], "02010605094e616d6505ff4c000103", ROWS[1][3]),
            ]
        ),
        private_key,
    )

    result = run_verify_raws(
        clean_database_url, storage_client, bytes(private_key), sample=5
    )

    # One changed raw is one key missing from the archive and one extra key in
    # it, so the count of differing (addr, scanned_at, raw) keys is 2.
    assert not result.ok
    assert result.mismatched == 2
    assert result.comparisons[0].archived == 2
    assert result.comparisons[0].old == 2


def test_verify_raws_reports_rows_missing_from_the_archive(
    clean_database_url: str,
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _prepare(clean_database_url, private_key)
    storage_client.blobs[BLOB_URI] = _encrypt(_jsonl(ROWS[:1]), private_key)

    result = run_verify_raws(
        clean_database_url, storage_client, bytes(private_key), sample=5
    )

    assert not result.ok
    assert result.comparisons[0].archived == 1
    assert result.comparisons[0].old == 2
    assert result.mismatched == 1


def test_verify_raws_skips_unroutable_blobs(clean_database_url: str) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _prepare(clean_database_url, private_key)
    _blob_row(clean_database_url, REJECTED_URI)

    result = run_verify_raws(
        clean_database_url, storage_client, bytes(private_key), sample=10
    )

    assert not result.ok
    assert result.mismatched == 0
    assert [uri for uri, _ in result.skipped] == [REJECTED_URI]
    assert storage_client.read_uris == [BLOB_URI]


def test_compare_blob_counts_only_scan_old_rows_of_that_blob(
    clean_database_url: str,
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    storage_client = _prepare(clean_database_url, private_key)
    other_uri = (
        "gs://test-bucket/scans/2026-08-01T00-05-00Z_device_0.0.4.jsonl.zst.encrypted"
    )
    _blob_row(clean_database_url, other_uri)
    _old_scan(
        clean_database_url,
        uri=other_uri,
        addr="AA:00:00:00:02:03",
        scanned_at="2026-08-01T00:05:00.000Z",
        raw=RAW,
        rssi=-70,
    )

    comparison = compare_blob(
        clean_database_url, storage_client, bytes(private_key), BLOB_URI
    )

    assert comparison.old == 2
    assert comparison.mismatched == 0


def test_verify_raws_command_exit_codes(
    clean_database_url: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    private_key = PrivateKey(bytes(range(32)))
    key_path = tmp_path / "key"
    key_path.write_bytes(base64.b64encode(bytes(private_key)))
    storage_client = _prepare(clean_database_url, private_key)
    monkeypatch.setattr(cli, "_build_storage_client", lambda *args: storage_client)

    arguments = [
        "verify-raws",
        "--database-url",
        clean_database_url,
        "--private-key",
        str(key_path),
        "--sample",
        "5",
    ]
    ok = CliRunner().invoke(cli.app, arguments)

    assert ok.exit_code == 0, ok.output
    assert "checked 1 objects, mismatched_keys=0, skipped=0" in ok.output

    storage_client.blobs[BLOB_URI] = _encrypt(_jsonl(ROWS[:1]), private_key)
    mismatch = CliRunner().invoke(cli.app, arguments)

    assert mismatch.exit_code == 1, mismatch.output
    assert "mismatched_keys=1" in mismatch.output
