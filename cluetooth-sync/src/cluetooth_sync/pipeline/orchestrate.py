import asyncio
import compression.zstd as zstd
from collections.abc import Iterator
from concurrent.futures import Executor
from dataclasses import dataclass

from rich.progress import (
    BarColumn,
    Progress,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
    TimeRemainingColumn,
)

from .decrypt import decrypt_blob_bytes
from .discover import discover_pending_blobs
from .download import read_blob_bytes
from .ingest import ingest_scan_jsonl_bytes
from .storage import StorageClient


@dataclass(frozen=True)
class BlobBytes:
    uri: str
    encrypted_bytes: bytes


def _read_blob(
    storage_client: StorageClient,
    blob_uri: str,
) -> BlobBytes:
    return BlobBytes(
        uri=blob_uri,
        encrypted_bytes=read_blob_bytes(storage_client, blob_uri),
    )


def _ingest_blob(
    database_url: str,
    private_key: bytes,
    blob: BlobBytes,
) -> None:
    decrypted_bytes = decrypt_blob_bytes(blob.encrypted_bytes, private_key)
    decompressed_bytes = zstd.decompress(decrypted_bytes)
    ingest_scan_jsonl_bytes(database_url, decompressed_bytes, blob.uri)


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
) -> int:
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

    download_queue: asyncio.Queue[str] = asyncio.Queue()
    ingest_queue: asyncio.Queue[BlobBytes] = asyncio.Queue(maxsize=queue_size)
    progress_queue: asyncio.Queue[None] = asyncio.Queue()

    async def download_worker() -> None:
        while True:
            try:
                blob_uri = await download_queue.get()
            except asyncio.QueueShutDown:
                return

            blob = await loop.run_in_executor(
                download_executor,
                _read_blob,
                storage_client,
                blob_uri,
            )
            await ingest_queue.put(blob)

    async def ingest_worker() -> None:
        while True:
            try:
                blob = await ingest_queue.get()
            except asyncio.QueueShutDown:
                return

            await loop.run_in_executor(
                ingest_executor,
                _ingest_blob,
                database_url,
                private_key,
                blob,
            )
            await progress_queue.put(None)

    async def progress_worker() -> None:
        completed_count = 0
        with Progress(
            TextColumn("[progress.description]{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            TextColumn("{task.completed}/{task.total}"),
            TimeElapsedColumn(),
            TimeRemainingColumn(),
        ) as progress:
            task_id = progress.add_task("sync blobs", total=total_blobs)
            while completed_count < total_blobs:
                await progress_queue.get()
                completed_count += 1
                progress.advance(task_id)

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
    return total_blobs
