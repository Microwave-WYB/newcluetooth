# Database schema: the minute index

The database does **not** store per-sighting rows any more. The uploaded GCS
objects are the raw archive: `cluetooth replay` reads them back, and
`cluetooth rebuild` regenerates aggregates from them. What the database keeps
is a small dictionary of exact advertisements plus one aggregate row per
sighting minute.

Definitions in `db/db/migrations/20261026000000_minute_index_schema.sql`; a
readable snapshot is `db/schema.sql`.

## Tables

### `ad_structures` — every distinct AD structure once

| column | meaning |
|---|---|
| `id` | identity |
| `ad_type` | the AD type byte (1, 9, 255, ...) |
| `data` | the structure's data bytes (without length and type bytes) |
| `local_name` | generated: UTF-8 name for types 8 and 9, trailing NULs removed |
| `company_id` | generated: little-endian company identifier for type 255 |
| `uuids` | generated: UUIDs for service and service-data structures |

Unique on `(ad_type, data)`. Matching rules (names, company fields, UUIDs) are
computed here once and reused by every payload, instead of being derived per
`(addr, raw)` pair.

### `payloads` — each exact advertisement once

| column | meaning |
|---|---|
| `sha256` | exact identity of the original bytes |
| `structure_ids` | the payload's structures, in order |
| `zero_padding` | zero bytes after the last structure (fixed-size buffers) |
| `tail` | non-zero remainder after the last structure (truncated field), else null |
| `first_seen`, `last_seen`, `sightings` | payload stats, updated by ingest |

There is no raw column. `payload_raw(id)` rebuilds the exact bytes (structures
in order, then `tail`, else `zero_padding` zero bytes), and ingest fails unless
every stored payload rebuilds to its `sha256`. `payload_fields` exposes the
former per-payload convenience fields: `local_name`, `adv_types`,
`ad_data_lengths`, `manufacturer_ids`, `service_uuids`, `service_data_uuids`.

### `sighting_minutes` — one row per minute, address, payload, producer

Partitioned by month on `minute`; minutes outside every partition land in
`sighting_minutes_default`.

Key: `unique nulls not distinct (minute, addr, payload_id, uploader_id,
session_id)`. The session is part of the key because v2 objects carry no
uploader identity; one row holds one payload.

| column group | columns | merge rule |
|---|---|---|
| provenance | `blob_counts` jsonb, generated `blob_ids` | per-blob counts are summed |
| counts | `sightings`, `first_seen`, `last_seen` | sum, min, max |
| signal | `rssi_min`, `rssi_max`, `rssi_sum`, `rssi_count`, generated `rssi_mean` | min, max, sum, count |
| best sample | `location` (geography), `best_rssi`, `best_accuracy`, `best_score`, `best_seen` | the better sample wins |
| extent | `min_lat`, `max_lat`, `min_lon`, `max_lon` | min/max of all phone positions |
| convenience | `tx_power`, `is_connectable` | max, any |
| reserved (v3) | `addr_type`, `adv_length`, `is_legacy`, `primary_phy`, `secondary_phy` | first non-null |

Every column is exact under merge, so a minute split across blobs, batches or
rebuilds equals a single pass. Indexes: the unique key (the merge upsert), the
`(id, minute)` primary key (WiGLE membership), `(addr, payload_id)` for
per-advertisement lookups, and GiST on `location`.

`ensure_sighting_partition(month)` creates a monthly partition. It returns
false when the default partition already holds rows for that month, because
such a partition cannot be created.

### Location is the minute's best sample

```
score = rssi / 20 - 2 * log10(accuracy_m)      -- accuracy missing: 50 m
accuracy_m = max(accuracy, 1)
```

Ties go to the better accuracy, then to the earlier time. The row stores the
sample's own accuracy (`best_accuracy` may be null) and its `best_score`.
**The coefficients are provisional** and are to be tuned against bought-device
ground truth.

### `adv_shapes` — a view, not a table

`adv_shapes` joins the per-`(addr, payload)` rollup of `sighting_minutes` with
`payload_fields`. It is a plain view: filter by `addr`/`payload_id` or join a
small candidate set instead of scanning it whole. Make it materialized only if
a whole-database analysis ever needs that.

### Other tables

- `uploaders` — producer tokens from legacy object names (`<ts>_<uploader>_<version>`).
- `blobs` — the processing ledger, now with `id`, `session_id`, `uploader_id`
  and the retry columns (`attempts`, `next_attempt_at`).
- `adv_enrichments` — passive enrichment output per `(addr, payload_id)`,
  keyed by `(addr, payload_id, enrichment_kind, enrichment_id, enrichment_revision)`.
  Built-in AD structure parsing is no longer stored here: it lives in
  `ad_structures`.
- `wigle_upload_batch_minutes` — WiGLE membership by minute row (`batch_id`,
  `minute_id`, `minute`), so one CSV row is one minute and nothing is uploaded
  twice.
- `backfill_progress` — resumability ledger for `cluetooth backfill`; dropped
  together with the `*_old` tables.

## Write paths

All three share `pipeline/sightings.py`: distinct raws → `raws` temp table →
structures and payloads → `batch` temp table → merge into `sighting_minutes`.

| command | source | payload stats | notes |
|---|---|---|---|
| `cluetooth sync` | new GCS objects | updated | one transaction per blob, shared advisory lock 4381022501 |
| `cluetooth backfill` | `scans_old` | updated | batched, resumable id ranges |
| `cluetooth rebuild` | archived GCS objects | untouched | one transaction, exclusive advisory lock |

## Reading raw data back

```sh
# one object
cluetooth replay --database-url ... --private-key key.txt --blob gs://bucket/scans/... \
  --format ndjson -o rows.ndjson

# every object behind a minute range, optionally one uploader
cluetooth replay --database-url ... --private-key key.txt \
  --uploader device --since 2026-09-01T00:00:00Z --until 2026-09-02T00:00:00Z \
  --format parquet -o range.parquet

# regenerate minutes of a range from the archive
cluetooth rebuild --database-url ... --private-key key.txt \
  --since 2026-09-01T00:00:00Z --until 2026-09-02T00:00:00Z

# pre-migration integrity check: sampled objects vs scans_old
cluetooth verify-raws --database-url ... --private-key key.txt --sample 10
```

## Names

There is no masking anywhere: the database, the WiGLE export and the wiki use
the full names parsed from the raw bytes. `raw` is authoritative and the
producer's `local_name` column was modified in place in production, so names
are derived from the advertisement (see `ad_structures.local_name`) and no
per-row name is stored.

## Migrating the old data

Before copying `scans_old`, `cluetooth verify-raws --sample N` replays N random
succeeded objects through the storage client and compares their raw bytes per
sighting with `scans_old`, reporting `archived`/`scans_old` counts and the
number of `(addr, scanned_at, raw)` keys whose multiplicity differs. It exits
nonzero on any mismatch or unroutable object; the runbook runs it before the
migration because the backfill copies the ledger, not the archive.

`cluetooth backfill [--batch-size N] [--verify-only]` aggregates `scans_old`
into minute rows in resumable id ranges and then runs
`queries/backfill/verify/*.sql`. A check passes when `old_value = new_value`;
checks named `*_info_*` are informational and always pass. The CLI exits
nonzero when a real check fails.

Run the verification before any new ingest: checks 01/02/05/07 compare
whole-table totals against `scans_old` and assume the new tables hold only
backfilled data.

## Renamed, not dropped

The previous tables are kept as `scans_old`, `advs_old`,
`adv_enrichments_old`, `wigle_upload_batch_scans_old` and
`projection_state_old`. They are dropped only by a later explicit migration
that the owner approves.

## Known limits

- v2 objects without a session footer have no producer identity: different
  phones' sightings in the same minute merge into one row.
- `sighting_minutes` checks the latitude/longitude ranges of its extent
  columns. The old per-row coordinate contract (paired lat/lon, accuracy
  requires coordinates and must be finite) is enforced on the ingest path in
  Python for v2 payloads, and is not a table constraint any more.
