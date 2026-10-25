import asyncio
import base64
import binascii
import math
import sys
import threading
import time
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, TextIO

import google.cloud.storage as gcs
import typer
from rich.progress import (
    BarColumn,
    Progress,
    TaskProgressColumn,
    TextColumn,
    TimeElapsedColumn,
    TimeRemainingColumn,
)

import polars as pl

from cluetooth_sync.pipeline import (
    GcsStorageClient,
    MirroredStorageClient,
    StorageClient,
    SyncStatus,
    blobs_in_range,
    check_heartbeat,
    rebuild_minutes,
    replay_sightings,
    run_backfill,
    run_pipeline,
    run_verify_raws,
    survey_blobs,
    write_heartbeat,
)
from cluetooth_sync.wigle import (
    WigleBatch,
    WigleExportResult,
    build_wigle_auth_header,
    count_pending_wigle_rows,
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


DEFAULT_HEARTBEAT_PATH = Path("/tmp/cluetooth-sync/heartbeat.json")
HEARTBEAT_INTERVAL_SECONDS = 15.0
MAX_CYCLE_ERROR_BACKOFF_SECONDS = 600


app = typer.Typer(
    name="cluetooth",
    no_args_is_help=True,
    help="Sync, export, and upload Cluetooth BLE observations.",
)
wigle_app = typer.Typer(
    no_args_is_help=True,
    help="Export and upload WiGLE-compatible Bluetooth CSV files.",
)


def _progress() -> Progress:
    return Progress(
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.completed}/{task.total}"),
        TimeElapsedColumn(),
        TimeRemainingColumn(),
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


def _validate_wigle_batch(batch: WigleBatch, *, retry_failed: bool) -> None:
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


def _upload_wigle_batch(
    database_url: str,
    *,
    batch: WigleBatch,
    work_dir: Path,
    capabilities: str,
    auth_header: str,
    donate: bool,
    timeout_seconds: int,
) -> tuple[WigleExportResult, tuple[str, ...]]:
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
        raise

    transids = mark_wigle_batch_uploaded(
        database_url,
        batch_id=batch.id,
        response=response,
    )
    return export_result, transids


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
    heartbeat_path: Annotated[
        Path,
        typer.Option(
            "--heartbeat-path",
            envvar="CLUETOOTH_HEARTBEAT_PATH",
            dir_okay=False,
            help="Heartbeat JSON file rewritten while sync runs.",
        ),
    ] = DEFAULT_HEARTBEAT_PATH,
    list_only: Annotated[
        bool,
        typer.Option(
            "--list-only",
            help=(
                "List storage objects and summarize them against the ledger by kind "
                "and month; no download, decryption, or database writes."
            ),
        ),
    ] = False,
) -> None:
    storage_client = _build_storage_client(service_account_key, mirror_dir)
    if list_only:
        _print_survey(storage_client, database_url, bucket_name, prefix)
        return

    private_key = _read_private_key(private_key_path)
    status = SyncStatus()
    continuous = poll_interval_seconds is not None
    stop = threading.Event()
    _start_daemon(_heartbeat_loop, status, heartbeat_path, stop)

    with (
        ThreadPoolExecutor(max_workers=download_workers) as download_executor,
        ThreadPoolExecutor(max_workers=ingest_workers) as ingest_executor,
    ):
        try:
            while True:
                status.begin_cycle()
                try:
                    stats = asyncio.run(
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
                            on_progress=status.cycle_progress,
                        )
                    )
                except Exception as exc:
                    # Transient GCS/DB outages must not end continuous sync.
                    if not continuous:
                        raise
                    status.cycle_failed(exc)
                    delay = min(
                        poll_interval_seconds * 2**status.consecutive_cycle_errors,
                        MAX_CYCLE_ERROR_BACKOFF_SECONDS,
                    )
                    print(
                        f"cycle error ({status.consecutive_cycle_errors} in a row), "
                        f"retry in {delay}s: {type(exc).__name__}: {exc}",
                        file=sys.stderr,
                        flush=True,
                    )
                    time.sleep(delay)
                    continue

                status.end_cycle(stats)
                print(
                    f"cycle {status.cycles} done: discovered={stats.discovered} "
                    f"ok={stats.succeeded} failed={stats.failed} rows={stats.rows} "
                    f"total_ok={status.total_succeeded} "
                    f"total_failed={status.total_failed}",
                    flush=True,
                )
                if not continuous:
                    break

                time.sleep(poll_interval_seconds)
        finally:
            stop.set()
            write_heartbeat(status, heartbeat_path)


def _start_daemon(target, *args) -> None:  # noqa: ANN001
    threading.Thread(target=target, args=args, daemon=True).start()


def _heartbeat_loop(status: SyncStatus, path: Path, stop: threading.Event) -> None:
    while True:
        try:
            write_heartbeat(status, path)
        except OSError as exc:
            print(f"heartbeat write failed: {exc}", file=sys.stderr, flush=True)
        if stop.wait(HEARTBEAT_INTERVAL_SECONDS):
            return


def _print_survey(
    storage_client: StorageClient,
    database_url: str,
    bucket_name: str,
    prefix: str,
) -> None:
    summary = survey_blobs(storage_client, database_url, bucket_name, prefix)
    with pl.Config(tbl_rows=-1, tbl_width_chars=200, tbl_hide_dataframe_shape=True):
        print(summary)
        print(
            summary.group_by("kind")
            .agg(pl.col("objects", "succeeded", "failed", "new").sum())
            .sort("kind")
        )


@app.command("health")
def health(
    heartbeat_path: Annotated[
        Path,
        typer.Option("--heartbeat-path", envvar="CLUETOOTH_HEARTBEAT_PATH"),
    ] = DEFAULT_HEARTBEAT_PATH,
    max_age_seconds: Annotated[
        float,
        typer.Option("--max-age-seconds", min=1, help="Max heartbeat age."),
    ] = 120,
    max_consecutive_errors: Annotated[
        int,
        typer.Option("--max-consecutive-errors", min=1),
    ] = 5,
) -> None:
    """Exit nonzero unless the sync heartbeat is fresh and not erroring."""
    healthy, message = check_heartbeat(
        heartbeat_path,
        max_age_seconds=max_age_seconds,
        max_consecutive_errors=max_consecutive_errors,
    )
    typer.echo(message)
    if not healthy:
        raise typer.Exit(code=1)


@app.command("backfill")
def backfill(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the target database.",
        ),
    ],
    batch_size: Annotated[
        int,
        typer.Option(
            "--batch-size",
            min=1,
            help="scans_old id range handled by one transaction.",
        ),
    ] = 100_000,
    verify_only: Annotated[
        bool,
        typer.Option(
            "--verify-only",
            help="Skip the aggregation and only run the verification checks.",
        ),
    ] = False,
) -> None:
    """Aggregate the old per-sighting table into sighting_minutes, then verify."""
    result = run_backfill(database_url, batch_size=batch_size, verify_only=verify_only)
    if not result.ok:
        raise typer.Exit(code=1)


def _parse_timestamp(value: str, option: str) -> datetime:
    """Parse an ISO timestamp; a value without an offset is read as UTC."""
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise typer.BadParameter(f"{option} must be an ISO timestamp") from exc
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _select_blob_uris(
    database_url: str,
    blob: list[str] | None,
    uploader: str | None,
    since: datetime | None,
    until: datetime | None,
) -> list[str]:
    """Objects to read: explicit --blob URIs, or those behind a minute range."""
    if blob:
        if uploader is not None or since is not None or until is not None:
            raise typer.BadParameter(
                "--blob cannot be combined with --uploader, --since or --until"
            )
        return list(blob)
    if since is None or until is None:
        raise typer.BadParameter("give --blob, or both --since and --until")
    return blobs_in_range(database_url, since, until, uploader)


def _write_replay(
    frames: Iterator[pl.DataFrame],
    output: Path | None,
    output_format: str,
) -> int:
    if output_format == "parquet":
        if output is None:
            raise typer.BadParameter("--format parquet needs --output")
        collected = [frame for frame in frames if not frame.is_empty()]
        frame = pl.concat(collected, how="diagonal") if collected else pl.DataFrame()
        output.parent.mkdir(parents=True, exist_ok=True)
        frame.write_parquet(output)
        return frame.height

    if output is None:
        return _write_replay_stream(frames, sys.stdout, output_format)

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        return _write_replay_stream(frames, handle, output_format)


def _write_replay_stream(
    frames: Iterator[pl.DataFrame],
    handle: TextIO,
    output_format: str,
) -> int:
    rows = 0
    wrote_header = False
    for frame in frames:
        if frame.is_empty():
            continue
        if output_format == "csv":
            frame.write_csv(handle, include_header=not wrote_header)
            wrote_header = True
        else:
            frame.write_ndjson(handle)
        rows += frame.height
    return rows


@app.command("replay")
def replay(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL of the ledger and minute tables.",
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
    blob: Annotated[
        list[str] | None,
        typer.Option(
            "--blob",
            help="Stored object URI to replay; repeat for several objects.",
        ),
    ] = None,
    uploader: Annotated[
        str | None,
        typer.Option(
            "--uploader",
            help="Only objects from this producer token.",
        ),
    ] = None,
    since: Annotated[
        str | None,
        typer.Option("--since", help="Range start (UTC when no offset)."),
    ] = None,
    until: Annotated[
        str | None,
        typer.Option("--until", help="Range end, exclusive."),
    ] = None,
    output: Annotated[
        Path | None,
        typer.Option(
            "--output",
            "-o",
            dir_okay=False,
            writable=True,
            help="Output file; stdout when omitted (ndjson and csv).",
        ),
    ] = None,
    output_format: Annotated[
        str,
        typer.Option("--format", help="ndjson, csv or parquet."),
    ] = "ndjson",
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
) -> None:
    """Read raw sightings back from the archived objects."""
    if output_format not in ("ndjson", "csv", "parquet"):
        raise typer.BadParameter("--format must be ndjson, csv or parquet")
    since_time = None if since is None else _parse_timestamp(since, "--since")
    until_time = None if until is None else _parse_timestamp(until, "--until")
    blob_uris = _select_blob_uris(database_url, blob, uploader, since_time, until_time)
    storage_client = _build_storage_client(service_account_key, mirror_dir)
    private_key = _read_private_key(private_key_path)

    rows = _write_replay(
        replay_sightings(
            storage_client,
            private_key,
            blob_uris,
            since=since_time,
            until=until_time,
        ),
        output,
        output_format,
    )
    print(
        f"replayed {rows} sightings from {len(blob_uris)} objects",
        file=sys.stderr,
        flush=True,
    )


@app.command("rebuild")
def rebuild(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL for the target database.",
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
    since: Annotated[
        str, typer.Option("--since", help="Range start (UTC when no offset).")
    ],
    until: Annotated[str, typer.Option("--until", help="Range end, exclusive.")],
    uploader: Annotated[
        str | None,
        typer.Option("--uploader", help="Only minutes of this producer token."),
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
) -> None:
    """Regenerate minute rows of a range from the archived objects."""
    result = rebuild_minutes(
        database_url,
        _build_storage_client(service_account_key, mirror_dir),
        _read_private_key(private_key_path),
        since=_parse_timestamp(since, "--since"),
        until=_parse_timestamp(until, "--until"),
        uploader=uploader,
    )
    typer.echo(f"rebuilt {result.sightings} sightings from {result.blobs} objects")


@app.command("verify-raws")
def verify_raws(
    database_url: Annotated[
        str,
        typer.Option(
            "--database-url",
            envvar="CLUETOOTH_DATABASE_URL",
            help="PostgreSQL URL of the ledger and scans_old.",
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
    sample: Annotated[
        int,
        typer.Option(
            "--sample",
            min=1,
            help="Number of succeeded blobs to replay and compare.",
        ),
    ] = 10,
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
) -> None:
    """Replay sampled archived objects and compare their raws with scans_old."""
    result = run_verify_raws(
        database_url,
        _build_storage_client(service_account_key, mirror_dir),
        _read_private_key(private_key_path),
        sample=sample,
    )
    for comparison in result.comparisons:
        print(
            f"check {comparison.uri} archived={comparison.archived} "
            f"scans_old={comparison.old} mismatched={comparison.mismatched}",
            flush=True,
        )
    for uri, reason in result.skipped:
        print(f"skip {uri}: {reason}", file=sys.stderr, flush=True)
    if not result.comparisons:
        print("no succeeded blobs to check", file=sys.stderr, flush=True)
    typer.echo(
        f"checked {len(result.comparisons)} objects, "
        f"mismatched_keys={result.mismatched}, skipped={len(result.skipped)}"
    )
    if not result.ok:
        raise typer.Exit(code=1)


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

    if batch_id is not None:
        batch = get_wigle_upload_batch(database_url, batch_id)
        if batch is None:
            typer.echo(f"batch {batch_id} not found")
            return

        _validate_wigle_batch(batch, retry_failed=retry_failed)
        with _progress() as progress:
            task_id = progress.add_task("upload WiGLE batches", total=1)
            try:
                export_result, transids = _upload_wigle_batch(
                    database_url,
                    batch=batch,
                    work_dir=work_dir,
                    capabilities=capabilities,
                    auth_header=auth_header,
                    donate=donate,
                    timeout_seconds=timeout_seconds,
                )
            except Exception as exc:
                typer.echo(str(exc), err=True)
                raise typer.Exit(code=1) from exc
            progress.advance(task_id)

        typer.echo(
            "uploaded "
            f"batch={batch.id} rows={export_result.row_count} "
            f"bytes={export_result.file_size} sha256={export_result.csv_sha256} "
            f"transids={','.join(transids) if transids else '(none)'}"
        )
        return

    first_batch = get_resumable_wigle_upload_batch(database_url)
    pending_row_count = count_pending_wigle_rows(database_url)
    resumable_row_count = 0 if first_batch is None else first_batch.row_count
    total_row_count = resumable_row_count + pending_row_count
    total_batches = math.ceil(pending_row_count / batch_size)
    if first_batch is not None:
        total_batches += 1

    print(
        f"discover {total_batches} pending WiGLE batches ({total_row_count} rows)",
        flush=True,
    )

    if total_batches == 0:
        typer.echo("no eligible scans to upload")
        return

    uploaded_batches = 0
    uploaded_rows = 0
    uploaded_bytes = 0
    all_transids: list[str] = []
    batch = first_batch
    with _progress() as progress:
        task_id = progress.add_task("upload WiGLE batches", total=total_batches)
        while True:
            if batch is None:
                batch = create_wigle_upload_batch(
                    database_url,
                    batch_size=batch_size,
                    filename=default_wigle_filename(),
                )
            if batch is None:
                break

            _validate_wigle_batch(batch, retry_failed=retry_failed)
            try:
                export_result, batch_transids = _upload_wigle_batch(
                    database_url,
                    batch=batch,
                    work_dir=work_dir,
                    capabilities=capabilities,
                    auth_header=auth_header,
                    donate=donate,
                    timeout_seconds=timeout_seconds,
                )
            except Exception as exc:
                typer.echo(str(exc), err=True)
                raise typer.Exit(code=1) from exc

            uploaded_batches += 1
            uploaded_rows += export_result.row_count
            uploaded_bytes += export_result.file_size
            all_transids.extend(batch_transids)
            progress.advance(task_id)
            batch = None

            if uploaded_batches >= total_batches:
                break

    typer.echo(
        "uploaded "
        f"batches={uploaded_batches} rows={uploaded_rows} bytes={uploaded_bytes} "
        f"transids={','.join(all_transids) if all_transids else '(none)'}"
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
                    "" if batch.min_item_id is None else str(batch.min_item_id),
                    "" if batch.max_item_id is None else str(batch.max_item_id),
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
