from collections.abc import Iterable
from pathlib import Path
from typing import Protocol

import google.cloud.storage as gcs


class StorageClient(Protocol):
    def list_blob_uris(self, bucket_name: str, prefix: str) -> Iterable[str]: ...

    def read_blob_bytes(self, blob_uri: str) -> bytes: ...


def parse_gcs_uri(blob_uri: str) -> tuple[str, str]:
    if not blob_uri.startswith("gs://"):
        raise ValueError(f"expected gs:// URI, got {blob_uri!r}")

    bucket_name, separator, blob_name = blob_uri.removeprefix("gs://").partition("/")
    if bucket_name == "" or separator == "" or blob_name == "":
        raise ValueError(f"expected gs://bucket/object URI, got {blob_uri!r}")

    return bucket_name, blob_name


class MirroredStorageClient:
    def __init__(self, upstream: StorageClient, mirror_dir: Path) -> None:
        self._upstream = upstream
        self._mirror_dir = mirror_dir

    def list_blob_uris(self, bucket_name: str, prefix: str) -> Iterable[str]:
        return self._upstream.list_blob_uris(bucket_name, prefix)

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        _, blob_name = parse_gcs_uri(blob_uri)
        mirror_path = self._mirror_dir / blob_name

        if mirror_path.exists():
            return mirror_path.read_bytes()

        blob_bytes = self._upstream.read_blob_bytes(blob_uri)
        mirror_path.parent.mkdir(parents=True, exist_ok=True)
        mirror_path.write_bytes(blob_bytes)
        return blob_bytes


class GcsStorageClient:
    def __init__(self, client: gcs.Client) -> None:
        self._client = client

    def list_blob_uris(self, bucket_name: str, prefix: str) -> Iterable[str]:
        for blob in self._client.list_blobs(bucket_name, prefix=prefix):
            yield f"gs://{bucket_name}/{blob.name}"

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        bucket_name, blob_name = parse_gcs_uri(blob_uri)
        return self._client.bucket(bucket_name).blob(blob_name).download_as_bytes()
