import asyncio
import base64
import binascii
import sys
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
from cluetooth_sync.wigle import (
    build_wigle_auth_header,
    create_wigle_upload_batch,
    default_wigle_filename,
    export_wigle_batch_csv,
    fetch_wigle_transactions,
    get_resumable_wigle_upload_batch,
    get_wigle_upload_batch,
    list_wigle_upload_batches,
    mark_wigle_batch_failed,
    mark_wigle_batch_uploaded,
    mark_wigle_batch_uploading,
    update_wigle_batch_statuses,
    upload_wigle_file,
    write_wigle_csv,
)


app = typer.Typer(
    name="cluetooth",
    no_args_is_help=True,
    help="Sync, export, and upload Cluetooth BLE observations.",
)
wigle_app = typer.Typer(
    no_args_is_help=True,
    help="Export and upload WiGLE-compatible Bluetooth CSV files.",
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


@app.command("sync")
def sync(
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


@wigle_app.command("export")
def wigle_export(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the source database.",
        ),
    ],
    output_path: Annotated[
        Path | None,
        typer.Option(
            "--output",
            "-o",
            file_okay=True,
            dir_okay=False,
            writable=True,
            help="Output CSV path. Defaults to stdout.",
        ),
    ] = None,
    batch_id: Annotated[
        int | None,
        typer.Option(
            "--batch-id",
            min=1,
            help="Export an exact tracked WiGLE batch instead of pending scans.",
        ),
    ] = None,
    limit: Annotated[
        int | None,
        typer.Option(
            "--limit",
            min=1,
            help="Maximum pending scan rows to export when --batch-id is omitted.",
        ),
    ] = None,
    include_uploaded: Annotated[
        bool,
        typer.Option(
            "--include-uploaded",
            help="Include scans already assigned to active or uploaded WiGLE batches.",
        ),
    ] = False,
    capabilities: Annotated[
        str,
        typer.Option(
            "--capabilities",
            help="Bluetooth capabilities text for WiGLE's AuthMode column.",
        ),
    ] = "Misc [LE]",
) -> None:
    if output_path is None:
        row_count = write_wigle_csv(
            database_url,
            sys.stdout,
            batch_id=batch_id,
            limit=limit,
            include_uploaded=include_uploaded,
            capabilities=capabilities,
        )
        print(f"exported {row_count} rows", file=sys.stderr, flush=True)
        return

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as output:
        row_count = write_wigle_csv(
            database_url,
            output,
            batch_id=batch_id,
            limit=limit,
            include_uploaded=include_uploaded,
            capabilities=capabilities,
        )
    typer.echo(f"exported {row_count} rows to {output_path}")


@wigle_app.command("upload")
def wigle_upload(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the source database.",
        ),
    ],
    api_key: Annotated[
        str | None,
        typer.Option(
            "--api-key",
            envvar="WIGLE_API_KEY",
            help="Pre-encoded WiGLE Basic auth key.",
        ),
    ] = None,
    batch_id: Annotated[
        int | None,
        typer.Option(
            "--batch-id",
            min=1,
            help="Upload an existing tracked batch instead of creating/resuming one.",
        ),
    ] = None,
    batch_size: Annotated[
        int,
        typer.Option(
            "--batch-size",
            envvar="CLUETOOTH_WIGLE_BATCH_SIZE",
            min=1,
            help="Maximum scan rows in a newly created WiGLE batch.",
        ),
    ] = 50_000,
    work_dir: Annotated[
        Path,
        typer.Option(
            "--work-dir",
            envvar="CLUETOOTH_WIGLE_WORK_DIR",
            file_okay=False,
            dir_okay=True,
            writable=True,
            help="Directory for generated WiGLE CSV files.",
        ),
    ] = Path("/tmp/cluetooth-wigle"),
    capabilities: Annotated[
        str,
        typer.Option(
            "--capabilities",
            help="Bluetooth capabilities text for WiGLE's AuthMode column.",
        ),
    ] = "Misc [LE]",
    donate: Annotated[
        bool,
        typer.Option(
            "--donate",
            help="Set WiGLE's donate=on upload flag for commercial-use donation.",
        ),
    ] = False,
    retry_failed: Annotated[
        bool,
        typer.Option(
            "--retry-failed",
            help="Allow explicit --batch-id upload of a failed batch.",
        ),
    ] = False,
    timeout_seconds: Annotated[
        int,
        typer.Option(
            "--timeout-seconds",
            min=1,
            help="HTTP timeout for WiGLE upload.",
        ),
    ] = 120,
) -> None:
    try:
        auth_header = build_wigle_auth_header(api_key=api_key)
    except ValueError as exc:
        raise typer.BadParameter(str(exc)) from exc

    if batch_id is None:
        batch = get_resumable_wigle_upload_batch(database_url)
        if batch is None:
            batch = create_wigle_upload_batch(
                database_url,
                batch_size=batch_size,
                filename=default_wigle_filename(),
            )
    else:
        batch = get_wigle_upload_batch(database_url, batch_id)

    if batch is None:
        typer.echo("no eligible scans to upload")
        return

    if batch.status == "failed" and not retry_failed:
        raise typer.BadParameter(
            f"batch {batch.id} is failed; pass --retry-failed to upload it again"
        )
    if batch.status == "uploading":
        raise typer.BadParameter(
            f"batch {batch.id} is marked uploading; inspect it before retrying"
        )
    if batch.status in ("uploaded", "completed"):
        raise typer.BadParameter(f"batch {batch.id} is already {batch.status}")

    filename = batch.filename or default_wigle_filename()
    output_path = work_dir / filename
    export_result = export_wigle_batch_csv(
        database_url,
        batch_id=batch.id,
        output_path=output_path,
        capabilities=capabilities,
    )
    mark_wigle_batch_uploading(database_url, batch_id=batch.id)

    try:
        response = upload_wigle_file(
            file_path=output_path,
            auth_header=auth_header,
            donate=donate,
            timeout_seconds=timeout_seconds,
        )
        if response.get("success") is False:
            raise RuntimeError(f"WiGLE upload failed: {response}")
    except Exception as exc:
        mark_wigle_batch_failed(
            database_url,
            batch_id=batch.id,
            error_message=str(exc),
        )
        typer.echo(str(exc), err=True)
        raise typer.Exit(code=1) from exc

    transids = mark_wigle_batch_uploaded(
        database_url,
        batch_id=batch.id,
        response=response,
    )
    typer.echo(
        "uploaded "
        f"batch={batch.id} rows={export_result.row_count} "
        f"bytes={export_result.file_size} sha256={export_result.csv_sha256} "
        f"transids={','.join(transids) if transids else '(none)'}"
    )


@wigle_app.command("status")
def wigle_status(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the source database.",
        ),
    ],
    api_key: Annotated[
        str | None,
        typer.Option("--api-key", envvar="WIGLE_API_KEY"),
    ] = None,
    page_start: Annotated[
        int,
        typer.Option("--page-start", min=0, help="WiGLE transaction page start."),
    ] = 0,
    page_end: Annotated[
        int,
        typer.Option("--page-end", min=1, help="WiGLE transaction page size."),
    ] = 100,
    timeout_seconds: Annotated[
        int,
        typer.Option("--timeout-seconds", min=1, help="HTTP timeout."),
    ] = 120,
) -> None:
    try:
        auth_header = build_wigle_auth_header(api_key=api_key)
    except ValueError as exc:
        raise typer.BadParameter(str(exc)) from exc

    response = fetch_wigle_transactions(
        auth_header=auth_header,
        page_start=page_start,
        page_end=page_end,
        timeout_seconds=timeout_seconds,
    )
    updated = update_wigle_batch_statuses(
        database_url,
        transactions_response=response,
    )
    typer.echo(f"updated {updated} uploaded batch statuses")


@wigle_app.command("batches")
def wigle_batches(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the source database.",
        ),
    ],
    limit: Annotated[
        int,
        typer.Option("--limit", min=1, help="Maximum batches to print."),
    ] = 20,
) -> None:
    batches = list_wigle_upload_batches(database_url, limit=limit)
    for batch in batches:
        typer.echo(
            "\t".join(
                [
                    str(batch.id),
                    batch.status,
                    str(batch.row_count),
                    "" if batch.min_scan_id is None else str(batch.min_scan_id),
                    "" if batch.max_scan_id is None else str(batch.max_scan_id),
                    batch.filename or "",
                    batch.wigle_status or "",
                    ",".join(batch.wigle_transids),
                ]
            )
        )


app.add_typer(wigle_app, name="wigle")


def main() -> None:
    app()


def sync_main() -> None:
    typer.run(sync)
