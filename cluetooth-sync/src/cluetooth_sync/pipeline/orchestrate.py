import asyncio
import compression.zstd as zstd
from collections.abc import Iterator
from concurrent.futures import Executor
from dataclasses import dataclass

import polars as pl

from .decrypt import decrypt_blob_bytes
from .discover import discover_pending_blobs
from .download import read_blob_bytes
from .ingest import insert_prepared_scans, prepare_scan_jsonl_bytes
from .storage import StorageClient


@dataclass(frozen=True)
class BlobBytes:
    uri: str
    encrypted_bytes: bytes


@dataclass(frozen=True)
class PreparedBlob:
    uri: str
    scans: pl.DataFrame


def _read_blob(
    storage_client: StorageClient,
    blob_uri: str,
) -> BlobBytes:
    return BlobBytes(
        uri=blob_uri,
        encrypted_bytes=read_blob_bytes(storage_client, blob_uri),
    )


def _prepare_blob(
    private_key: bytes,
    blob: BlobBytes,
) -> PreparedBlob:
    decrypted_bytes = decrypt_blob_bytes(blob.encrypted_bytes, private_key)
    decompressed_bytes = zstd.decompress(decrypted_bytes)
    scans = prepare_scan_jsonl_bytes(decompressed_bytes, blob.uri)
    return PreparedBlob(uri=blob.uri, scans=scans)


def _write_blob(
    database_url: str,
    blob: PreparedBlob,
) -> None:
    insert_prepared_scans(database_url, blob.scans)


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
) -> None:
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
    print(f"0/{total_blobs}", flush=True)

    download_queue: asyncio.Queue[str] = asyncio.Queue()
    prepare_queue: asyncio.Queue[BlobBytes] = asyncio.Queue(maxsize=queue_size)
    write_queue: asyncio.Queue[PreparedBlob] = asyncio.Queue(maxsize=queue_size)
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
            await prepare_queue.put(blob)

    async def prepare_worker() -> None:
        while True:
            try:
                blob = await prepare_queue.get()
            except asyncio.QueueShutDown:
                return

            prepared_blob = await loop.run_in_executor(
                ingest_executor,
                _prepare_blob,
                private_key,
                blob,
            )
            await write_queue.put(prepared_blob)

    async def write_worker() -> None:
        while True:
            try:
                blob = await write_queue.get()
            except asyncio.QueueShutDown:
                return

            await loop.run_in_executor(
                ingest_executor,
                _write_blob,
                database_url,
                blob,
            )
            await progress_queue.put(None)

    async def progress_worker() -> None:
        completed_count = 0
        while completed_count < total_blobs:
            await progress_queue.get()
            completed_count += 1
            print(f"{completed_count}/{total_blobs}", flush=True)

    download_tasks = [
        asyncio.create_task(download_worker()) for _ in range(download_workers)
    ]
    prepare_tasks = [
        asyncio.create_task(prepare_worker()) for _ in range(ingest_workers)
    ]
    write_task = asyncio.create_task(write_worker())
    progress_task = asyncio.create_task(progress_worker())

    for blob_uri in discovered_blobs:
        await download_queue.put(blob_uri)

    download_queue.shutdown()
    _ = await asyncio.gather(*download_tasks)

    prepare_queue.shutdown()
    _ = await asyncio.gather(*prepare_tasks)

    write_queue.shutdown()
    await write_task
    await progress_task
