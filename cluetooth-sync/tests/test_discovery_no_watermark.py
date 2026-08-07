import pytest

from cluetooth_sync.pipeline.discover import discover_pending_blobs

OLDER_URI = (
    "gs://bucket/scans/v2/2025/03/24/"
    "0195c920-7c00-7abc-8def-0123456789ab.parquet.encrypted"
)
NEWER_URI = (
    "gs://bucket/scans/v2/2025/03/25/"
    "0195ca99-5000-7aaa-aaaa-aaaaaaaaaaaa.parquet.encrypted"
)


class _DelayedStorage:
    def __init__(self) -> None:
        self.listings = [[NEWER_URI], [OLDER_URI, NEWER_URI]]

    def list_blob_uris(self, bucket_name: str, prefix: str) -> list[str]:
        return self.listings.pop(0)

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        raise AssertionError("discovery is read-only")


def test_full_listing_discovers_delayed_older_uuid_without_date_watermark(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "cluetooth_sync.pipeline.discover._successful_blob_uris",
        lambda database_url, uri_prefix: {NEWER_URI},
    )
    storage = _DelayedStorage()

    assert list(discover_pending_blobs(storage, "db", "bucket", "scans/")) == []
    assert list(discover_pending_blobs(storage, "db", "bucket", "scans/")) == [
        OLDER_URI
    ]
