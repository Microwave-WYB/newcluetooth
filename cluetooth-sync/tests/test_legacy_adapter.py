import pytest

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
