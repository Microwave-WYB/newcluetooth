import asyncio
import compression.zstd as zstd
import sys
import time
from collections.abc import Callable, Iterator
from concurrent.futures import Executor
from dataclasses import dataclass

from .decrypt import decrypt_blob_bytes
from .discover import discover_pending_blobs
from .download import read_blob_bytes
from .ingest import (
    insert_prepared_scans,
    mark_blob_failed,
    mark_blob_succeeded_empty,
    prepare_scan_jsonl_bytes,
)
from .payload_route import PayloadRouteKind, route_payload_uri
from .payload_v2 import prepare_payload_v2_parquet_bytes
from .storage import StorageClient


PROGRESS_LOG_INTERVAL_SECONDS = 30.0


@dataclass(frozen=True)
class BlobBytes:
    uri: str
    encrypted_bytes: bytes


@dataclass
class CycleStats:
    discovered: int = 0
    completed: int = 0
    succeeded: int = 0
    failed: int = 0
    rows: int = 0


def _record_failure(database_url: str, blob_uri: str, exc: Exception) -> None:
    print(f"failed {blob_uri}: {exc}", file=sys.stderr, flush=True)
    try:
        mark_blob_failed(database_url, blob_uri, exc)
    except Exception as mark_exc:
        # The blob stays unsucceeded in the ledger, so the next cycle retries it.
        print(
            f"could not record failure for {blob_uri}: {mark_exc}",
            file=sys.stderr,
            flush=True,
        )


def _read_blob(
    storage_client: StorageClient,
    blob_uri: str,
) -> BlobBytes:
    return BlobBytes(
        uri=blob_uri,
        encrypted_bytes=read_blob_bytes(storage_client, blob_uri),
    )


def _read_blob_bookkept(
    storage_client: StorageClient,
    database_url: str,
    blob_uri: str,
) -> BlobBytes | None:
    try:
        # Validate/routable paths before a mirrored client derives a local path.
        route_payload_uri(blob_uri)
        return _read_blob(storage_client, blob_uri)
    except Exception as exc:
        _record_failure(database_url, blob_uri, exc)
        return None


def _ingest_blob(
    database_url: str,
    private_key: bytes,
    blob: BlobBytes,
) -> int | None:
    """Return inserted row count, or None after recording a failure."""
    try:
        route = route_payload_uri(blob.uri)
        decrypted_bytes = decrypt_blob_bytes(blob.encrypted_bytes, private_key)
        if route.kind is PayloadRouteKind.LEGACY_JSONL_ZSTD:
            decompressed_bytes = zstd.decompress(decrypted_bytes)
            scans = prepare_scan_jsonl_bytes(decompressed_bytes, blob.uri)
        else:
            scans = prepare_payload_v2_parquet_bytes(decrypted_bytes, route)

        if scans.is_empty():
            mark_blob_succeeded_empty(database_url, blob.uri)
            return 0
        return insert_prepared_scans(
            database_url, scans, mark_failure=False, skip_if_succeeded=True
        )
    except Exception as exc:
        _record_failure(database_url, blob.uri, exc)
        return None


async def run_pipeline(
    storage_client: StorageClient,
    database_url: str,
    bucket_name: str,
    prefix: str,
    private_key: bytes,
    download_executor: Executor,
    ingest_executor: Executor,
    download_workers: int,
    ingest_workers: int,
    queue_size: int = 0,
    max_blobs: int | None = None,
    on_progress: Callable[[CycleStats], None] | None = None,
) -> CycleStats:
    loop = asyncio.get_running_loop()
    blob_uris: Iterator[str] = discover_pending_blobs(
        storage_client=storage_client,
        database_url=database_url,
        bucket_name=bucket_name,
        prefix=prefix,
    )
    discovered_blobs = list(blob_uris)
    if max_blobs is not None:
        discovered_blobs = discovered_blobs[:max_blobs]

    total_blobs = len(discovered_blobs)
    print(f"discover {total_blobs} pending blobs", flush=True)
    stats = CycleStats(discovered=total_blobs)
    if on_progress is not None:
        on_progress(stats)

    download_queue: asyncio.Queue[str] = asyncio.Queue()
    ingest_queue: asyncio.Queue[BlobBytes] = asyncio.Queue(maxsize=queue_size)
    progress_queue: asyncio.Queue[int | None] = asyncio.Queue()

    async def download_worker() -> None:
        while True:
            try:
                blob_uri = await download_queue.get()
            except asyncio.QueueShutDown:
                return

            blob = await loop.run_in_executor(
                download_executor,
                _read_blob_bookkept,
                storage_client,
                database_url,
                blob_uri,
            )
            if blob is None:
                await progress_queue.put(None)
            else:
                await ingest_queue.put(blob)

    async def ingest_worker() -> None:
        while True:
            try:
                blob = await ingest_queue.get()
            except asyncio.QueueShutDown:
                return

            rows = await loop.run_in_executor(
                ingest_executor,
                _ingest_blob,
                database_url,
                private_key,
                blob,
            )
            await progress_queue.put(rows)

    async def progress_worker() -> None:
        started = time.monotonic()
        last_log = started
        while stats.completed < total_blobs:
            rows = await progress_queue.get()
            stats.completed += 1
            if rows is None:
                stats.failed += 1
            else:
                stats.succeeded += 1
                stats.rows += rows
            if on_progress is not None:
                on_progress(stats)

            now = time.monotonic()
            if (
                now - last_log >= PROGRESS_LOG_INTERVAL_SECONDS
                or stats.completed == total_blobs
            ):
                last_log = now
                rate = stats.completed / max(now - started, 1e-9)
                eta_seconds = (total_blobs - stats.completed) / max(rate, 1e-9)
                print(
                    f"progress {stats.completed}/{total_blobs} "
                    f"ok={stats.succeeded} failed={stats.failed} rows={stats.rows} "
                    f"rate={rate:.2f} blobs/s eta={eta_seconds / 60:.1f} min",
                    flush=True,
                )

    download_tasks = [
        asyncio.create_task(download_worker()) for _ in range(download_workers)
    ]
    ingest_tasks = [asyncio.create_task(ingest_worker()) for _ in range(ingest_workers)]
    progress_task = asyncio.create_task(progress_worker())

    for blob_uri in discovered_blobs:
        await download_queue.put(blob_uri)

    download_queue.shutdown()
    _ = await asyncio.gather(*download_tasks)

    ingest_queue.shutdown()
    _ = await asyncio.gather(*ingest_tasks)
    await progress_task
    return stats
