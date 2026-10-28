import pytest

from cluetooth_sync.pipeline.discover import discover_pending_blobs, distinct_prefixes
from cluetooth_sync.pipeline.orchestrate import _discover

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
        "cluetooth_sync.pipeline.discover._skipped_blob_uris",
        lambda database_url, uri_prefix: {NEWER_URI},
    )
    storage = _DelayedStorage()

    assert list(discover_pending_blobs(storage, "db", "bucket", "scans/")) == []
    assert list(discover_pending_blobs(storage, "db", "bucket", "scans/")) == [
        OLDER_URI
    ]


def test_overlapping_prefixes_are_listed_once() -> None:
    assert distinct_prefixes(["scans/", "gatt/", "scans/", "scans/v3/"]) == [
        "scans/",
        "gatt/",
    ]
    assert distinct_prefixes(["scans/v3/", "scans/v2/"]) == ["scans/v3/", "scans/v2/"]
    assert distinct_prefixes([]) == []


def test_max_blobs_applies_per_prefix_so_scans_cannot_starve_gatt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pending = {
        "scans/": [f"gs://bucket/scans/{n}" for n in range(5)],
        "gatt/": [f"gs://bucket/gatt/{n}" for n in range(3)],
    }
    listed: list[str] = []

    def fake(**kwargs: str) -> object:
        listed.append(kwargs["prefix"])
        return iter(pending[kwargs["prefix"]])

    monkeypatch.setattr(
        "cluetooth_sync.pipeline.orchestrate.discover_pending_blobs", fake
    )

    found = _discover(object(), "db", "bucket", ["scans/", "gatt/", "scans/v2/"], 2)  # type: ignore[arg-type]
    assert found == [
        "gs://bucket/scans/0",
        "gs://bucket/scans/1",
        "gs://bucket/gatt/0",
        "gs://bucket/gatt/1",
    ]
    assert listed == ["scans/", "gatt/"]
    assert len(_discover(object(), "db", "bucket", ["scans/", "gatt/"], None)) == 8  # type: ignore[arg-type]
