import hashlib
import os
import tempfile
import threading
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


_CACHE_LOCKS_GUARD = threading.Lock()
_CACHE_LOCKS: dict[Path, threading.Lock] = {}


def _cache_lock(path: Path) -> threading.Lock:
    with _CACHE_LOCKS_GUARD:
        return _CACHE_LOCKS.setdefault(path, threading.Lock())


class MirroredStorageClient:
    def __init__(self, upstream: StorageClient, mirror_dir: Path) -> None:
        self._upstream = upstream
        self._mirror_root = mirror_dir.resolve()

    def list_blob_uris(self, bucket_name: str, prefix: str) -> Iterable[str]:
        return self._upstream.list_blob_uris(bucket_name, prefix)

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        _, blob_name = parse_gcs_uri(blob_uri)
        if blob_name.startswith("/") or any(
            component in {"", ".", ".."} for component in blob_name.split("/")
        ):
            raise ValueError(f"refusing unsafe mirror object path {blob_name!r}")
        mirror_path = self._contained_path(self._mirror_root / blob_name, blob_name)
        marker_name = f"{hashlib.sha256(blob_uri.encode()).hexdigest()}.complete"
        marker_path = self._contained_path(
            self._mirror_root / ".cluetooth-cache-metadata" / marker_name,
            blob_name,
        )

        with _cache_lock(mirror_path):
            cached = self._read_verified_cache(mirror_path, marker_path)
            if cached is not None:
                return cached

            blob_bytes = self._upstream.read_blob_bytes(blob_uri)
            digest = hashlib.sha256(blob_bytes).hexdigest()
            self._publish_atomically(mirror_path, blob_bytes)
            marker = f"{len(blob_bytes)}\n{digest}\n".encode()
            self._publish_atomically(marker_path, marker)
            return blob_bytes

    def _contained_path(self, candidate: Path, blob_name: str) -> Path:
        resolved = candidate.resolve()
        try:
            resolved.relative_to(self._mirror_root)
        except ValueError as exc:
            raise ValueError(
                f"refusing mirror path outside root for object {blob_name!r}"
            ) from exc
        return resolved

    @staticmethod
    def _read_verified_cache(mirror_path: Path, marker_path: Path) -> bytes | None:
        try:
            marker_lines = marker_path.read_text(encoding="ascii").splitlines()
            if len(marker_lines) != 2:
                return None
            expected_size = int(marker_lines[0])
            expected_digest = marker_lines[1]
            if expected_size < 0 or len(expected_digest) != 64:
                return None
            payload = mirror_path.read_bytes()
        except FileNotFoundError, OSError, UnicodeError, ValueError:
            return None
        if len(payload) != expected_size:
            return None
        if hashlib.sha256(payload).hexdigest() != expected_digest:
            return None
        return payload

    @staticmethod
    def _publish_atomically(path: Path, payload: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary_name: str | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="wb",
                dir=path.parent,
                prefix=".cluetooth-cache-",
                suffix=".tmp",
                delete=False,
            ) as temporary:
                temporary_name = temporary.name
                temporary.write(payload)
                temporary.flush()
                os.fsync(temporary.fileno())
            os.replace(temporary_name, path)
            temporary_name = None
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if temporary_name is not None:
                Path(temporary_name).unlink(missing_ok=True)


class GcsStorageClient:
    def __init__(self, client: gcs.Client) -> None:
        self._client = client

    def list_blob_uris(self, bucket_name: str, prefix: str) -> Iterable[str]:
        for blob in self._client.list_blobs(bucket_name, prefix=prefix):
            yield f"gs://{bucket_name}/{blob.name}"

    def read_blob_bytes(self, blob_uri: str) -> bytes:
        bucket_name, blob_name = parse_gcs_uri(blob_uri)
        return self._client.bucket(bucket_name).blob(blob_name).download_as_bytes()
