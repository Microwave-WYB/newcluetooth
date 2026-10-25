"""Pre-migration integrity check: does the archive still match `scans_old`?

The uploaded objects are the raw archive and `raw` is authoritative, but the
convenience columns of `scans_old` were modified in place at some point, so the
migration runbook samples blobs, replays each object and compares its raw bytes
per sighting with the old table. A blob whose object no longer matches, or that
cannot be routed, is reported instead of being silently accepted.
"""

from dataclasses import dataclass

import cluetooth_sync.pipeline.db as db
from cluetooth_sync.pipeline.download import read_blob_bytes
from cluetooth_sync.pipeline.ingest import stage_frame, write_stage
from cluetooth_sync.pipeline.orchestrate import BlobBytes, parse_blob
from cluetooth_sync.pipeline.payload_route import PayloadRoutingError, route_payload_uri
from cluetooth_sync.pipeline.queries import read_query, stage_query
from cluetooth_sync.pipeline.storage import StorageClient


@dataclass(frozen=True)
class BlobComparison:
    uri: str
    archived: int
    old: int
    mismatched: int


@dataclass(frozen=True)
class VerifyRawsResult:
    comparisons: tuple[BlobComparison, ...]
    skipped: tuple[tuple[str, str], ...]

    @property
    def mismatched(self) -> int:
        return sum(comparison.mismatched for comparison in self.comparisons)

    @property
    def ok(self) -> bool:
        return self.mismatched == 0 and not self.skipped


def run_verify_raws(
    database_url: str,
    storage_client: StorageClient,
    private_key: bytes,
    *,
    sample: int,
) -> VerifyRawsResult:
    """Replay `sample` random succeeded blobs and compare them with scans_old."""
    uris = sample_blob_uris(database_url, sample)
    comparisons: list[BlobComparison] = []
    skipped: list[tuple[str, str]] = []
    for uri in uris:
        try:
            comparisons.append(
                compare_blob(database_url, storage_client, private_key, uri)
            )
        except PayloadRoutingError as exc:
            skipped.append((uri, str(exc)))
    return VerifyRawsResult(comparisons=tuple(comparisons), skipped=tuple(skipped))


def sample_blob_uris(database_url: str, sample: int) -> list[str]:
    with db.session(database_url) as session:
        session.cursor.execute(read_query("verify_raws/sample_blobs.sql"), [sample])
        return session.cursor.fetch_polars().get_column("uri").to_list()


def compare_blob(
    database_url: str,
    storage_client: StorageClient,
    private_key: bytes,
    uri: str,
) -> BlobComparison:
    """One object: route, decrypt, parse, then compare raw bytes with scans_old."""
    route_payload_uri(uri)
    blob = BlobBytes(uri=uri, encrypted_bytes=read_blob_bytes(storage_client, uri))
    stage = stage_frame(parse_blob(blob, private_key))
    with db.session(database_url) as session:
        staging_table = write_stage(session, stage)
        session.cursor.execute(
            stage_query("verify_raws/compare.sql", stage=staging_table), [uri]
        )
        row = session.cursor.fetch_polars().row(0, named=True)
    return BlobComparison(
        uri=uri,
        archived=int(row["archived"]),
        old=int(row["old"]),
        mismatched=int(row["mismatched"]),
    )
