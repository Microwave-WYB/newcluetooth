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
  - Uses mise-managed `dbmate`.
  - Local test database config lives under `db/test/`.
- `cluetooth-core/`
  - Owns platform-neutral observation batching, pending Parquet state (new
    payloads are schema v3, v2 stays readable) and local `gatt/v1` object storage.
  - Owns the on-device wiki rule matcher (`src/matcher/`, `RuleMatcher` over UniFFI).
  - Uses Rust, Polars, UUIDv7, Zstd Parquet, and UniFFI.
- `cluetooth-android/`
  - Owns BLE/location/UI/Firebase/WorkManager platform integration. Phase 4
    forwards new observations to Rust, uploads Rust-prepared ciphertext (scan v2
    and v3, `gatt/v1`), runs the manual GATT engine (debug trigger only), and
    retains strict pre-0.0.5 legacy pending-file upload.
  - Also keeps the phone-local stores (`local/`: seen products, recordings,
    sighting history, route) and the build-time wiki bundle exporter
    (`tools/wiki-bundle/`, Node); see `docs/local-data.md`.
- `cluetooth-sync/`
  - Owns the sync and ingestion pipeline.
  - Uses Python, Polars, ADBC PostgreSQL, PyNaCl, Google Cloud Storage, Typer,
    Ruff, basedpyright, pytest, and testcontainers.
- `cluetooth-agents/`
  - Placeholder for agent/research-oriented code.

Payload contracts: `docs/payload-schema-v2.md` (identity, batching, upload
state), `docs/payload-schema-v3.md` (scan v3 columns/footer and `gatt/v1`).
`docs/recording-model.md`: live scanning (foreground, no session, no rows) versus
recording (foreground service, core session, rows, upload).

## Database Model

The database is defined in `db/db/migrations/`; `docs/schema.md` is the prose
description and `db/schema.sql` a generated snapshot.

There are **no per-sighting rows**. The uploaded GCS objects are the raw
archive (`cluetooth replay` reads them back, `cluetooth rebuild` regenerates
aggregates), and the database holds a dictionary of exact advertisements plus
one aggregate row per sighting minute:

- `ad_structures`
  - Every distinct AD structure once, unique on `(ad_type, data)`.
  - Generated `local_name`, `company_id` and `uuids`, so matching rules are
    computed once instead of per `(addr, raw)`.
- `payloads`
  - Every distinct advertisement once: `sha256`, ordered `structure_ids`,
    `zero_padding`, `tail`, and the `first_seen`/`last_seen`/`sightings` stats.
  - No raw column; `payload_raw(id)` rebuilds the exact bytes and ingest fails
    unless every payload round-trips.
  - `payload_fields` is the view with `local_name`, `adv_types`,
    `ad_data_lengths`, `manufacturer_ids`, `service_uuids`,
    `service_data_uuids`.
- `sighting_minutes`
  - One row per `(minute, addr, payload_id, uploader_id, session_id)`, unique
    with nulls not distinct, partitioned by month on `minute` (a default
    partition catches producer clocks far off).
  - Every column is exact under merge: `blob_counts`/`blob_ids` provenance,
    `sightings`, first/last seen, RSSI min/max/sum/count/mean, spatial extent,
    `tx_power`, `is_connectable`, `adv_tx_power`, `periodic_interval`, and the
    minute's best sample (`location`, `best_rssi`, `best_accuracy`,
    `best_score`, `best_seen`).
  - The producer v3 PDU fields `addr_type`, `is_legacy`, `primary_phy`,
    `secondary_phy`, `adv_sid` and `data_status` are part of the key (null for
    legacy and v2); see `docs/schema.md` ("Ingesting scan v3 and gatt/v1") and
    the producer contract `docs/payload-schema-v3.md`.
  - The best sample's score coefficients are **provisional**.
- `adv_shapes`
  - A plain **view** over `sighting_minutes` plus `payload_fields`. Filter or
    join a small candidate set; do not scan it whole.
- `uploaders`, `blobs`
  - Producer tokens and the processing ledger (`blobs` also has `id`,
    `uploader_id`, `session_id`, `attempts`, `next_attempt_at`, and `meta`
    with the v3 Parquet footer or the GATT device fields).
- `gatt_discoveries`, `gatt_attributes`
  - One row per `gatt/v1` discovery (trigger advertisement linked to
    `payloads`, outcome, stage, error, timings, `profile_hash`, full JSON in
    `raw`) and one row per service/characteristic/descriptor. They replaced the
    legacy tables of the same names, which were never written.
- `adv_enrichments`
  - Passive enrichment per `(addr, payload_id, kind, id, revision)`; built-in
    AD structure parsing lives in `ad_structures` instead.
- `wigle_upload_batch_minutes`
  - WiGLE membership by minute row. The export is one CSV row per address per
    minute (best located row, latest non-empty name); a batch claims every row
    of the address-minute, so nothing is uploaded twice.
- `backfill_progress`
  - Resumability ledger for `cluetooth backfill`.

Important schema rules:

- `raw` is authoritative for adv payload identity; `sha256` is its exact
  identity in the database.
- `(minute, addr, payload, uploader, session)` is the stable aggregate key.
- Anything richer than raw payload identity belongs in `adv_enrichments`,
  GATT discovery tables, or later research/analysis outputs.
- The previous tables are renamed, never dropped: `scans_old`, `advs_old`,
  `adv_enrichments_old`, `wigle_upload_batch_scans_old`,
  `projection_state_old`. Only an explicit migration the owner approves removes
  them.
- The GCS objects must never be deleted; see `docs/operations.md`.

## Adv Parsing

Scan ingest does not decode advertisements. Each raw payload is split into its
low-level AD structures by `ble_adv_split(raw)`:

- it preserves order and duplicates;
- it stops at a zero length or a truncated field instead of repairing;
- the unparsed remainder becomes `zero_padding` (all zero bytes) or `tail`
  (anything else, e.g. a truncated field);
- Python `cluetooth_sync.ble.parse_raw` follows the same stop rule and is used
  by the legacy adapter to extract names.

`ad_structures` stores each distinct structure once with generated
`local_name`, `company_id` and `uuids`; `payloads` stores the ordered structure
ids plus the remainder. Nothing protocol-specific is decoded at ingest.

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
  - Always performs a full prefix listing; there is no UUID/date watermark, so
    delayed older objects remain discoverable.
- `download.py`
  - Reads one encrypted blob as bytes through an explicit `StorageClient`.
- `decrypt.py`
  - Decrypts ciphertext bytes with PyNaCl `SealedBox` and a 32-byte private key.
- `sightings.py`
  - The shared write path: distinct raws of a batch into a temporary `raws`
    table, new structures into `ad_structures`, new payloads into `payloads`
    (failing unless every payload round-trips), normalized sightings into a
    temporary `batch` table, then the merge into `sighting_minutes` (plus
    payload stats, except for rebuild).
  - Also owns monthly partition creation.
- `backfill.py`
  - `cluetooth backfill`: batched, resumable aggregation of `scans_old` into
    minute rows, then the `queries/backfill/verify/*.sql` checks.
- `verify_raws.py`
  - `cluetooth verify-raws`: the pre-migration integrity check. Samples
    succeeded blobs, replays each object and compares its raw bytes per
    sighting with `scans_old` (`queries/verify_raws/`), reporting mismatched
    `(addr, scanned_at, raw)` keys and unroutable objects.
- `replay.py`
  - `cluetooth replay` (raw sightings of archived objects) and
    `cluetooth rebuild` (regenerate a minute range from the archive).
- `payload_route.py` / `payload_v2.py` / `payload_v3.py`
  - Strictly route legacy `0.0.1`-`0.0.4` JSONL, nested schema-v2 and schema-v3
    Parquet URIs and `gatt/v1` JSON URIs.
  - Validate v2/v3 UUID/date/footer/exact schema/required values and convert binary raw
    bytes to the existing lowercase-hex staging representation. v3 also returns
    the `cluetooth.*` footer entries for `blobs.meta`.
- `gatt_v1.py` / `gatt_ingest.py`
  - Validate one `gatt/v1` JSON object (including `profile_hash`) and write it,
    its attributes and the trigger payload in one transaction. Note that
    Polars' ADBC `write_database` commits the session's transaction, so stage
    every frame before the writes that must stay atomic.
- `ingest.py`
  - Parses JSONL with a hand-written Polars schema.
  - Handles current scan payloads and legacy `0.0.1` through `0.0.4`
    scan blobs detected from the blob filename.
  - Stages rows with Polars/ADBC and calls `sightings.py`, so payloads, minute
    rows and `success = true` commit in one transaction.
  - Retries retryable deadlock/serialization SQL states, then marks the blob
    failed on final failure.
  - ADBC cannot bind a null parameter: absent uploader/app version/session
    values travel as `-1`/`''` sentinels and the SQL restores the null.
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
- download and ingest run as separate `asyncio` worker stages
- stages are connected with `asyncio.Queue`
- encrypted blob bytes are the in-memory handoff between download and ingest
- legacy routing accepts only flat `scans/` objects from producers 0.0.1-0.0.4;
  every schema-bearing or unsafe/nested legacy path is rejected
- the CLI discovers `scans/` and `gatt/` by default (`--prefix` is repeatable)
- local mirroring resolves destinations beneath the resolved mirror root and
  rejects traversal and existing symlink escapes
- optional mirroring in `storage.py` can cache encrypted blobs on disk
- ingest workers route by URI, decrypt with `SealedBox`, then either decompress
  legacy outer-Zstd JSONL or read v2 Parquet directly
- blocking storage and ingest work runs in caller-provided executors
- progress is printed to stdout as `0/N`, `1/N`, ...

The Typer CLI in `cluetooth_sync.cli` builds the storage client, reads the
private key, creates thread pools, and calls `run_pipeline(...)`. It supports
environment variables for database URL, bucket, prefix, private key path,
service account key, mirror dir, worker counts, queue size, polling interval,
and max blobs.

In continuous mode (`--poll-interval-seconds`), cycle errors back off and retry
instead of exiting; failed blobs retry with exponential backoff
(`blobs.attempts`, `next_attempt_at`). A heartbeat JSON file backs
`cluetooth health`; `--list-only` inventories GCS against the ledger without
downloads or writes. There is no projection thread and no materialized view to
refresh. Ingest holds shared advisory lock 4381022501 and `rebuild` takes it
exclusively. See `docs/operations.md`.

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
- per-row `local_name` is not stored: names always come from the raw
  advertisement (there is no masking anywhere, in the database, the WiGLE
  export or the wiki)

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
- apply migrations with mise-managed `dbmate` on PATH
- use fixture JSONL for current and legacy scan payloads
- verify inserted data with Polars database reads
- assert on minute rows directly; there are no materialized views to refresh
- `clean_database_url` (conftest) gives a test its own freshly migrated
  database when it asserts whole-table state, as the backfill verification does

From the root, `mise install` installs host tools in project-isolated Rust homes;
`mise run install` syncs locked Python packages. `mise run check` serializes Rust,
Android host JVM/lint, then Python checks/DB tests (Docker). Python tests first
prebuild the Rust encryption example via mise. Guarded Android host tasks leave
Google Services unapplied only for exact JVM/lint selectors and exclude native
packaging, never compilation/JVM tests/lint; success
is not proof of APK/native/device behavior. SDK licenses/packages and all four
Android targets/cargo-ndk use explicit setup tasks; see `cluetooth-android/README.md`.
Product config is production-associated and does not match `.debug`. Do not run
app/device/cloud operations in default checks or read service-account secrets.

For the local test database use `mise run //db:testdb-up`,
`mise run //db:testdb-wait` (one readiness check),
`mise run //db:testdb-migrate`, and `mise run //db:testdb-down`.

## Wiki

`wiki/` is a **record of identified Bluetooth advertisement families**. It is not
itself a lookup tool: the records are meant to be compiled later into a
machine-built, queryable index, so do not hand-maintain lookup or ambiguity
tables.

- One page per **advertisement-structure / protocol family**, not per brand or
  marketing product line. Entries whose advertisements are very similar (same
  company field, service UUID scheme, AD-structure layout, name-token
  convention, or vendor protocol across models) live on one page; split into
  separate pages when the structure or protocol clearly differs, even within one
  brand. One brand with several unrelated ad formats gets several pages; several
  brands sharing one protocol or ODM ad format may share a page. Name the page
  after the protocol/shape family and mention the brands in the title or intro.
  Decide only from evidence already recorded; never invent technical claims to
  justify a grouping.
- Every family page keeps a consistent "How to recognize it" block (local name,
  company field, service/service-data UUID, ordered AD shape, what it is,
  confidence and limits) followed by the evidence. Treat it as structured data
  for a future indexer and keep the notation identical everywhere: company
  fields as `company field N` (decimal), 16-bit UUIDs uppercase (`FEA0`),
  128-bit UUIDs lowercase, AD shape as `type/length` pairs in observed order
  (`01/1,09/17,FF/7`). Add the block to older pages only as a pure restatement
  of identifiers already in the page.
- `wiki/README.md` is a plain list of the family pages with a short description,
  plus the evidence-reading notes. It is not a hand-made lookup index.
- Aggregate or cross-family pages may exist only as thin indexes linking to
  family pages; they must not hold entry content.
- New findings go into the matching family page; create one only if none fits.
- Keep the existing evidence rules: candidates are not authenticated identities,
  and no MACs, raw payloads, locations, device-level UUIDs, WiGLE data or secrets.

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

## Android UI Principles

These rules come from the owner. Every change to `cluetooth-android/app/.../ui/` must follow them. If a request seems to conflict with them, ask the owner.

**Scope**
- Implement exactly what was asked. Do not redesign, restyle, reorder or "clean up" screens that nobody asked about. Raise extra ideas as proposals and wait for an explicit yes.
- Never remove a feature or a screen as a side effect.

**Components and style**
- Use stock Material 3 components with their default parameters: `Scaffold`, `TopAppBar`, `NavigationBar`, `ModalBottomSheet`, `ListItem`, `Button`, `FilledTonalButton`, `SegmentedButton` and `FloatingActionButton`.
- Do not pass style parameters such as `contentPadding`, `border`, `colors`, `shape`, custom elevation or `fontSize`. There are no hex colors and no monospace font.
- To make two actions look alike, use the same component for both. Do not override one to imitate the other.
- The only agreed overrides are:
  - the RSSI band colors (green, amber, red);
  - the red idle record FAB with a white dot;
  - `loweredElevation` on the record and scroll-to-top FABs;
  - the 1 dp shadow on the record control and pill;
  - the chart and sparkline `Canvas`;
  - the blinking dot.
- The UI is English only. Use plain text labels, no check or cross symbols.

- Never show the same information twice on one screen, for example a title in the top bar and again in the body.

**Lists**
- A list the user can tap must never re-order by itself. Sort once, when the list appears or the user changes the sort. After that, rows keep their positions: values update in place and new rows are appended. Only an explicit action re-sorts (pull-to-refresh or changing the sort).
- Enforce this in the code structure, not by watching the screen. Each list keeps an explicit order state (a list of keys) that only user actions change. Live data only updates the values of keys already in it and appends new keys. Cover it with a unit test that feeds changing RSSI and asserts the order is unchanged.
- Scan sorts once by RSSI 3 s after the first device. Stale rows dim in place.
- Opening an item from a list and coming back (with Back, by closing the sheet or by returning from a page) must restore the list exactly where it was: the same scroll position, the same order and the same sheet height. The user must never have to scroll down again to reach the next item.

**Vocabulary**
- Use "scan", never "heard": "Scanned now", "8 scanned now", "Last scanned 2 h ago".
- A device row shows the device's own advertised name, live or stored from recordings. If it has none, show "No name". Never fall back to the product or wiki title.
- Do not show the address type. Show RSSI as "−56 dBm", small and inline.

**Recording**
- Recording starts only from the FAB on Scan. While recording, the top-right pill (blinking dot plus time) appears on every other page. It is an overlay, so no title-bar slot is reserved, and tapping it opens the control bar (pause/resume and stop).
- On Scan, the expanded control sits at the bottom right: blinking dot, time, pause and stop.
- No snackbar after a successful stop. Errors only.
- History hides recordings that have no data.

**Device sheet**
- The sheet wraps its content and never covers the top app bar.
- The chart covers the last 1 minute only, with no window selector. The end marker is the current reading, with the same value and color as the number. There are no band text labels, only the −65 and −80 ticks.
- Discover GATT is a filled `Button`. View on map and Wiki are `FilledTonalButton`s.

**Maps**
- Map pages have no top app bar, so the map fills the screen under the status bar. System back leaves the page. The page title (for example the recording time, distance and device count) goes in the sheet's peek header.
- The map sheet never covers the whole map. Fully expanded, it stops at about 60% of the screen height, so the map always stays visible above it.
- The density (heat) layer is on by default. It is drawn with circles; `HeatmapLayer` crashes on the Adreno 640 test phone, so do not use it.

**Filters**
- "Hide unnamed Apple devices" is on by default. It hides every 0x004C device without a local name and affects the UI only, never recording or data.

**Verification**
- Before handing over a build, run it on the emulator AVD `cluetooth-test`, and on the test phone `R58M81J3YJV` when it's connected. Check every screen you touched, and check `logcat -b crash`. Behaviour that the code guarantees (list order, state restoration) is proven by unit tests, not by watching the screen for a long time.
- Name APKs `cluetooth-0.1.0-debug-<sha8>.apk`. Never overwrite an existing APK.
