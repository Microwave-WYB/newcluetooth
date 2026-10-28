import re
from collections.abc import Sequence

import polars as pl

from cluetooth_sync.pipeline.discover import distinct_prefixes
from cluetooth_sync.pipeline.storage import StorageClient

_DATED_MONTH = re.compile(
    r"/(?:scans/v[23]|gatt/v1)/(?P<year>[0-9]{4})/(?P<month>[0-9]{2})/"
)
_ANY_MONTH = re.compile(r"(?P<year>20[0-9]{2})[-/](?P<month>[0-9]{2})")


def _kind(uri: str, bucket_name: str) -> str:
    object_name = uri.removeprefix(f"gs://{bucket_name}/")
    if object_name.startswith("scans/v2/"):
        return "v2"
    if object_name.startswith("scans/v3/"):
        return "v3"
    if object_name.startswith("gatt/v1/"):
        return "gatt/v1"
    if object_name.startswith("scans/schema=v2/"):
        return "schema=v2 (historical, rejected)"
    if object_name.startswith("scans/") and "/" not in object_name.removeprefix(
        "scans/"
    ):
        return "legacy flat"
    return "other"


def _month(uri: str) -> str:
    # v2, v3 and gatt/v1 paths carry the UTC date; legacy filenames start with a timestamp.
    filename = uri.rsplit("/", 1)[-1]
    match = (
        _DATED_MONTH.search(uri) or _ANY_MONTH.match(filename) or _ANY_MONTH.search(uri)
    )
    if match is None:
        return "unknown"
    return f"{match.group('year')}-{match.group('month')}"


def survey_blobs(
    storage_client: StorageClient,
    database_url: str,
    bucket_name: str,
    prefixes: Sequence[str],
) -> pl.DataFrame:
    """List-only inventory: a GCS listing plus one read-only ledger SELECT per prefix."""
    rows = []
    for prefix in distinct_prefixes(prefixes):
        uri_prefix = f"gs://{bucket_name}/{prefix}"
        # Columns that exist before and after the retry-backoff migration.
        ledger = pl.read_database_uri(
            "select uri, success from blobs where left(uri, length($1)) = $1",
            database_url,
            engine="adbc",
            execute_options={"parameters": [uri_prefix]},
        )
        ledger_status = dict(
            zip(
                ledger.get_column("uri").to_list(),
                ledger.get_column("success").to_list(),
                strict=True,
            )
        )
        for uri in storage_client.list_blob_uris(bucket_name, prefix):
            success = ledger_status.get(uri)
            status = (
                "succeeded"
                if success is True
                else "failed"
                if uri in ledger_status
                else "new"
            )
            rows.append(
                {
                    "kind": _kind(uri, bucket_name),
                    "month": _month(uri),
                    "status": status,
                }
            )

    schema = {"kind": pl.Utf8, "month": pl.Utf8, "status": pl.Utf8}
    return (
        pl.DataFrame(rows, schema=schema)
        .group_by("kind", "month")
        .agg(
            pl.len().alias("objects"),
            (pl.col("status") == "succeeded").sum().alias("succeeded"),
            (pl.col("status") == "failed").sum().alias("failed"),
            (pl.col("status") == "new").sum().alias("new"),
        )
        .sort("kind", "month")
    )
