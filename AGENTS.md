# newcluetooth

This repo collects high-fidelity BLE observations and processes them into a
Postgres/PostGIS database for later analysis.

The design goal is:

- keep ingestion simple and fast
- preserve raw adv payloads as the source of truth
- parse only minimal low-level adv structure during ingest
- defer richer enrichment, decoding, and interaction analysis to later stages

## Layout

- `db/`
  - Owns the database schema and migrations.
  - Uses `dbmate`.
  - Local test database config lives under `db/test/`.
- `cluetooth-sync/`
  - Owns the sync and ingestion pipeline.
  - Uses Python, Polars, ADBC PostgreSQL, PyNaCl, Google Cloud Storage, Typer,
    Ruff, basedpyright, pytest, and testcontainers.
- `cluetooth-agents/`
  - Placeholder for agent/research-oriented code.

## Database Model

The database is defined in `db/db/migrations/`.

Current important tables and materialized views:

- `blobs`
  - Processing ledger for source blobs.
  - `uri` is the primary key.
  - Tracks optional uploader/app metadata plus sync success/failure state.
  - Discovery is read-only against this table; processing stages create or update
    rows as blobs are ingested.
- `scans`
  - One row per observed BLE adv.
  - `raw` is the authoritative adv payload (`bytea`).
  - Convenience fields include `local_name`, `tx_power`, and `is_connectable`.
  - Location fields are `lat`, `lon`, `accuracy`, and computed PostGIS
    `location`.
  - There is intentionally no rich decoded adv column on `scans`.
- `gatt_discoveries`
  - One row per active GATT discovery result.
  - Stores the observed device, discovery time, optional raw adv,
    `raw_profile`, `profile_hash`, and location.
- `gatt_attributes`
  - Attribute rows for a GATT discovery.
  - Keyed by `(discovery_id, attribute_id)`.
  - Stores parent relationship, attribute kind, UUID, and raw structured data.
- `adv_enrichments`
  - Passive enrichment output for exact `(addr, raw)` adv identity.
  - Keyed by `(addr, raw, enrichment_kind, enrichment_id, enrichment_revision)`.
  - Built-in AD structure parsing is stored here as:
    - `enrichment_kind = 'builtin'`
    - `enrichment_id = 'ad_structures'`
    - `enrichment_revision = '20260416210856_init'`
- `recipe_interaction_runs`
  - Active recipe CLI sessions against a device/adv.
  - Stores recipe identity, target address, timing, status, and result JSON.
- `recipe_interaction_events`
  - Structured transcript/BLE events for a recipe interaction run.
- `adv_observations`
  - Materialized rollup by `blob`, `addr`, and `raw`.
  - Includes latest local name, first/last seen, average RSSI, centroid, radius,
    and scan count.
- `advs`
  - Materialized rollup by `addr` and `raw`.
  - Represents exact producer address plus exact adv payload identity.
- `devices`
  - Materialized rollup by device address.
  - Answers where/when a MAC has been observed across payload variants.
- `payloads`
  - Materialized rollup by raw adv payload.
  - Answers where/when a payload has been observed across addresses.

Important schema rules:

- `raw` is authoritative for adv payload identity.
- `(addr, raw)` is the stable key for exact advs.
- Anything richer than raw payload identity belongs in:
  - a convenience field on `scans`,
  - `adv_enrichments`,
  - GATT discovery tables, or
  - later research/analysis outputs.
- Materialized rollups are not automatically refreshed by ingest. Tests refresh
  `adv_observations`, `advs`, `devices`, and `payloads`
  explicitly before asserting rollup state.

## Adv Parsing

On scan insert, the database trigger `scans_upsert_adv_structure`
populates `adv_enrichments` with built-in low-level AD structures.

`parse_ble_adv(raw)` in the init migration parses raw BLE adv bytes
into JSON like:

```json
[
  { "type": 1, "data": "06" },
  { "type": 9, "data": "..." },
  { "type": 255, "data": "..." }
]
```

Notes:

- `parse_ble_adv(raw)` returns only a list of low-level AD structures.
- It preserves order.
- It preserves duplicates.
- It does not do protocol-specific decoding.
- It exits on zero-length or truncated AD fields rather than trying to repair
  malformed payloads.
- Python `cluetooth_sync.ble.parse_raw` exists for lightweight local parsing,
  currently used by the legacy adapter to extract local names. The database
  parser remains the canonical built-in ingest enrichment.

## Python Sync Pipeline

`cluetooth-sync/src/cluetooth_sync/pipeline/` is split by stage:

- `storage.py`
  - Defines the `StorageClient` protocol.
  - Provides `GcsStorageClient`, `MirroredStorageClient`, and `parse_gcs_uri`.
  - The mirror client is an optional local cache for downloaded encrypted blobs.
- `discover.py`
  - Read-only discovery against GCS plus DB sync state.
  - Lists storage blobs under `gs://{bucket}/{prefix}`.
  - Skips only blob URIs already marked `success is true`.
  - Failed blobs are considered pending and may be retried.
- `download.py`
  - Reads one encrypted blob as bytes through an explicit `StorageClient`.
- `decrypt.py`
  - Decrypts ciphertext bytes with PyNaCl `SealedBox` and a 32-byte private key.
- `ingest.py`
  - Parses JSONL with a hand-written Polars schema.
  - Handles current scan payloads and legacy `0.0.1`, `0.0.2`, and `0.0.3`
    scan blobs detected from the blob filename.
  - Writes a temporary staging table with Polars/ADBC, inserts into `scans`,
    and marks the blob succeeded in one transaction.
  - Retries retryable deadlock/serialization SQL states, then marks the blob
    failed on final failure.
- `legacy_adapter.py`
  - Converts legacy scan JSONL (`mac`, `timestamp`, base64 `raw`) into the
    current scan schema.
  - Extracts legacy local names from AD type `0x09` or `0x08` when possible.
- `orchestrate.py`
  - Coordinates discovery, download, decrypt, decompress, and ingest.
  - `run_pipeline(...)` is the main orchestration entrypoint.

Current orchestration model:

- discovery is synchronous and materialized into a list for the current cycle
- `max_blobs` optionally truncates that discovered list
- download, prepare, and write run as separate `asyncio` worker stages
- stages are connected with `asyncio.Queue`
- encrypted blob bytes are the in-memory handoff between download and prepare
- prepared Polars DataFrames are the in-memory handoff between prepare and write
- optional mirroring in `storage.py` can cache encrypted blobs on disk
- prepare workers decrypt with `SealedBox`, decompress with zstd, and normalize
  JSONL into Polars DataFrames
- one write worker inserts prepared scans into Postgres, so DB writes are
  serialized even when preparation is parallel
- blocking storage, prepare, and write work runs in caller-provided executors
- progress is printed to stdout as `0/N`, `1/N`, ...

The Typer CLI in `cluetooth_sync.cli` builds the storage client, reads the
private key, creates thread pools, and calls `run_pipeline(...)`. It supports
environment variables for database URL, bucket, prefix, private key path,
service account key, mirror dir, worker counts, queue size, polling interval,
and max blobs.

## Ingest Rules

The ingest path is intentionally narrow:

- it assumes upstream arguments are already valid
- it uses a hand-written Polars schema instead of schema inference
- it keeps `db/scan.schema.json` and `SCAN_POLARS_SCHEMA` manually aligned
- it does not perform defensive GCS lookup logic in ingest
- it stages rows through a temporary database table before inserting

Current JSONL contract:

- every current scan row must include every defined column:
  - `addr`
  - `rssi`
  - `scanned_at`
  - `raw`
  - `local_name`
  - `tx_power`
  - `is_connectable`
  - `lat`
  - `lon`
  - `accuracy`
- nullable fields must still be present with `null`
- `addr` is normalized to uppercase in Polars before database insertion
- `raw` is normalized to lowercase hex before database insertion
- `scanned_at` is parsed strictly as UTC datetime
- RSSI value `127` is treated as unavailable and converted to null
- SQL inserts cast `addr` to `macaddr`, `rssi`/`tx_power` to `smallint`, and
  decode `raw` from hex to `bytea`

## Logging / Observability

Pipeline services write operational progress to stdout/stderr so container logs
are directly useful during migrations and production sync.

Important current messages:

- orchestration prints discovered pending blob count and progress to stdout
- ingest retries and final blob failures are printed to stderr

## Testing

`cluetooth-sync/tests/` contains integration-style tests for ingest and pipeline
stages.

Current test approach:

- use `testcontainers` to start PostGIS
- apply migrations with the repo-local `dbmate` setup
- use fixture JSONL for current and legacy scan payloads
- verify inserted data with Polars database reads
- refresh materialized views explicitly when tests need rollup state

Run from `cluetooth-sync/`:

- `make test`
- `make check`

Run from `db/` for the local test database:

- `make testdb-up`
- `make testdb-migrate`
- `make testdb-down`

## Conventions

- Prefer simple stage boundaries over defensive wrappers.
- If a function interacts with GCS, pass a `StorageClient` or GCS client
  explicitly.
- Keep stage modules independent.
- Do not introduce abstraction layers unless they materially simplify the
  current code.
- Keep ingestion fast; defer expensive enrichment and protocol-specific decoding
  to later stages.
- Preserve exact raw payload bytes whenever possible; derive additional meaning
  into separate enrichment or analysis outputs.
