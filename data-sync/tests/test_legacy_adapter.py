import pytest

from cluetooth_sync.ble import AdStruct, parse_raw
from cluetooth_sync.pipeline.legacy_adapter import is_legacy_scan_blob


@pytest.mark.parametrize(
    "version",
    [
        "0.0.1",
        "0.0.2",
        "0.0.3",
        "0.0.4",
        "0.0.4-debug",
        "0.0.4+build",
    ],
)
def test_legacy_scan_versions_remain_compatible(version: str) -> None:
    uri = (
        "gs://test-bucket/scans/"
        f"2026-04-10T19-37-46Z_device_{version}.jsonl.zst.encrypted"
    )

    assert is_legacy_scan_blob(uri)


@pytest.mark.parametrize(
    "version",
    ["0.0.0", "0.0.5", "0.0.5-debug", "0.0.5+build", "1.0.0", "v0.0.4"],
)
def test_nonlegacy_scan_versions_are_not_misclassified(version: str) -> None:
    uri = f"gs://test-bucket/scans/timestamp_device_{version}.jsonl.zst.encrypted"

    assert not is_legacy_scan_blob(uri)


def test_parse_raw_stops_at_a_truncated_field_like_the_sql_tokenizer() -> None:
    assert parse_raw(bytes.fromhex("020106")) == [AdStruct(ad_type=1, data=b"\x06")]
    # The second field claims two bytes but only its length byte is present.
    assert parse_raw(bytes.fromhex("02010602")) == [AdStruct(ad_type=1, data=b"\x06")]
    # A lone length byte has no type byte at all.
    assert parse_raw(bytes.fromhex("02")) == []
