from collections.abc import Iterator

import polars as pl

from cluetooth_sync.pipeline.queries import read_query
from cluetooth_sync.pipeline.storage import StorageClient


def _uri_prefix(bucket_name: str, prefix: str) -> str:
    return f"gs://{bucket_name}/{prefix}"


def _successful_blob_uris(database_url: str, uri_prefix: str) -> set[str]:
    rows = pl.read_database_uri(
        read_query("blobs/list_succeeded_with_prefix.sql"),
        database_url,
        engine="adbc",
        execute_options={"parameters": [uri_prefix]},
    )
    return set(rows.get_column("uri").to_list())


def discover_pending_blobs(
    storage_client: StorageClient,
    database_url: str,
    bucket_name: str,
    prefix: str,
) -> Iterator[str]:
    uri_prefix = _uri_prefix(bucket_name, prefix)
    synced_uris = _successful_blob_uris(database_url, uri_prefix)

    for blob_uri in storage_client.list_blob_uris(bucket_name, prefix):
        if blob_uri in synced_uris:
            continue

        yield blob_uri
