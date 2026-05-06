import asyncio
import base64
import binascii
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Annotated

import google.cloud.storage as gcs
import typer

from cluetooth_sync.pipeline import (
    GcsStorageClient,
    MirroredStorageClient,
    StorageClient,
    insert_builtin_ad_structures,
    project_advs,
    run_pipeline,
)


def _read_private_key(path: Path) -> bytes:
    raw_key = path.read_bytes()
    compact_key = b"".join(raw_key.split())

    try:
        decoded_key = base64.b64decode(compact_key, validate=True)
    except binascii.Error:
        decoded_key = raw_key.strip()

    if len(decoded_key) != 32:
        raise typer.BadParameter("private key must decode to 32 bytes")

    return decoded_key


def _build_storage_client(
    service_account_key: Path | None,
    mirror_dir: Path | None,
) -> StorageClient:
    if service_account_key is None:
        gcs_client = gcs.Client()
    else:
        gcs_client = gcs.Client.from_service_account_json(str(service_account_key))

    storage_client: StorageClient = GcsStorageClient(gcs_client)
    if mirror_dir is not None:
        storage_client = MirroredStorageClient(storage_client, mirror_dir)

    return storage_client


def run(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the target database.",
        ),
    ],
    bucket_name: Annotated[
        str,
        typer.Option(
            "--bucket",
            envvar="CLUETOOTH_BUCKET",
            help="Storage bucket containing scan blobs.",
        ),
    ],
    private_key_path: Annotated[
        Path,
        typer.Option(
            "--private-key",
            envvar="CLUETOOTH_PRIVATE_KEY_PATH",
            exists=True,
            file_okay=True,
            dir_okay=False,
            readable=True,
            help="Base64 or raw 32-byte private key for decrypting blobs.",
        ),
    ],
    prefix: Annotated[
        str,
        typer.Option(
            "--prefix",
            envvar="CLUETOOTH_PREFIX",
            help="Object prefix to discover.",
        ),
    ] = "scans/",
    mirror_dir: Annotated[
        Path | None,
        typer.Option(
            "--mirror-dir",
            envvar="CLUETOOTH_MIRROR_DIR",
            file_okay=False,
            dir_okay=True,
            writable=True,
            help="Optional local mirror directory for cached blobs.",
        ),
    ] = None,
    service_account_key: Annotated[
        Path | None,
        typer.Option(
            "--service-account-key",
            envvar="GOOGLE_APPLICATION_CREDENTIALS",
            exists=True,
            file_okay=True,
            dir_okay=False,
            readable=True,
            help="Optional Google service account JSON key.",
        ),
    ] = None,
    download_workers: Annotated[
        int,
        typer.Option(
            "--download-workers",
            envvar="CLUETOOTH_DOWNLOAD_WORKERS",
            min=1,
            help="Number of workers reading encrypted blobs.",
        ),
    ] = 4,
    ingest_workers: Annotated[
        int,
        typer.Option(
            "--ingest-workers",
            envvar="CLUETOOTH_INGEST_WORKERS",
            min=1,
            help="Number of workers decrypting, decompressing, and ingesting blobs.",
        ),
    ] = 2,
    queue_size: Annotated[
        int,
        typer.Option(
            "--queue-size",
            envvar="CLUETOOTH_QUEUE_SIZE",
            min=0,
            help="Max encrypted blobs buffered in memory; 0 means unbounded.",
        ),
    ] = 0,
    poll_interval_seconds: Annotated[
        int | None,
        typer.Option(
            "--poll-interval-seconds",
            envvar="CLUETOOTH_POLL_INTERVAL_SECONDS",
            min=1,
            help="Rerun sync every N seconds; omit to run once.",
        ),
    ] = None,
    max_blobs: Annotated[
        int | None,
        typer.Option(
            "--max-blobs",
            envvar="CLUETOOTH_MAX_BLOBS",
            min=1,
            help="Only process up to N pending blobs per sync cycle.",
        ),
    ] = None,
) -> None:
    private_key = _read_private_key(private_key_path)
    storage_client = _build_storage_client(service_account_key, mirror_dir)

    with (
        ThreadPoolExecutor(max_workers=download_workers) as download_executor,
        ThreadPoolExecutor(max_workers=ingest_workers) as ingest_executor,
    ):
        while True:
            processed_count = asyncio.run(
                run_pipeline(
                    storage_client=storage_client,
                    database_url=database_url,
                    bucket_name=bucket_name,
                    prefix=prefix,
                    private_key=private_key,
                    download_executor=download_executor,
                    ingest_executor=ingest_executor,
                    download_workers=download_workers,
                    ingest_workers=ingest_workers,
                    queue_size=queue_size,
                    max_blobs=max_blobs,
                )
            )
            if processed_count > 0:
                project_advs(database_url)
                print("project advs", flush=True)
                insert_builtin_ad_structures(database_url)

            if poll_interval_seconds is None:
                break

            time.sleep(poll_interval_seconds)


def main() -> None:
    typer.run(run)
