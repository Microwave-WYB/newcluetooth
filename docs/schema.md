# Database schema: the minute index

The database does **not** store per-sighting rows any more. The uploaded GCS
objects are the raw archive: `cluetooth replay` reads them back, and
`cluetooth rebuild` regenerates aggregates from them. What the database keeps
is a small dictionary of exact advertisements plus one aggregate row per
sighting minute.

Definitions in `db/db/migrations/20261026000000_minute_index_schema.sql` and
`20261027120000_payload_v3_gatt.sql` (the v3 key and the GATT tables); a
readable snapshot is `db/schema.sql`. The producer contracts are
`docs/payload-schema-v2.md` and `docs/payload-schema-v3.md` (the producer
contracts, owned by the core branch); the server-side rules are under
"Ingesting scan v3 and gatt/v1" below.

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
session_id, addr_type, is_legacy, primary_phy, secondary_phy, adv_sid,
data_status)`. The session is part of the key because v2 objects carry no
uploader identity; one row holds one payload. The last six are the producer v3
PDU fields (null for legacy and v2 sightings): they say which kind of
advertisement was received, so the same bytes seen as a legacy PDU and as an
extended PDU on another PHY or set are separate rows. See "Aggregation key"
below for why they are key columns.

| column group | columns | merge rule |
|---|---|---|
| provenance | `blob_counts` jsonb, generated `blob_ids` | per-blob counts are summed |
| counts | `sightings`, `first_seen`, `last_seen` | sum, min, max |
| signal | `rssi_min`, `rssi_max`, `rssi_sum`, `rssi_count`, generated `rssi_mean` | min, max, sum, count |
| best sample | `location` (geography), `best_rssi`, `best_accuracy`, `best_score`, `best_seen` | the better sample wins |
| extent | `min_lat`, `max_lat`, `min_lon`, `max_lon` | min/max of all phone positions |
| convenience | `tx_power`, `is_connectable` | max, any |
| v3 measurements | `adv_tx_power`, `periodic_interval` | max |
| v3 key columns | `addr_type`, `is_legacy`, `primary_phy`, `secondary_phy`, `adv_sid`, `data_status` | part of the key |
| reserved | `adv_length` | unused (equals the payload's raw length) |

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
  and the retry columns (`attempts`, `next_attempt_at`). `meta` (jsonb) holds
  the Parquet footer entries of a v3 file (`cluetooth.*` keys without the
  prefix, e.g. `device_model`, `app_version`)
  or the device and app fields of a GATT object; `app_version` is filled from
  the footer when the object name carries none.
- `adv_enrichments` — passive enrichment output per `(addr, payload_id)`,
  keyed by `(addr, payload_id, enrichment_kind, enrichment_id, enrichment_revision)`.
  Built-in AD structure parsing is no longer stored here: it lives in
  `ad_structures`.
- `wigle_upload_batch_minutes` — WiGLE membership by minute row (`batch_id`,
  `minute_id`, `minute`). The export unit is one **address-minute**: see "WiGLE
  export" below.
- `backfill_progress` — resumability ledger for `cluetooth backfill`; dropped
  together with the `*_old` tables.

### `gatt_discoveries` and `gatt_attributes` — manual GATT discoveries

Replaced the legacy tables of the same names (never written by any code; the
migration aborts if they hold rows). One `gatt/v1` object is one
`gatt_discoveries` row, linked to its `blobs` row by `blob_id` (unique) and
identified by `discovery_uuid` (the object's UUIDv7).

| column group | columns |
|---|---|
| source | `blob_id`, `discovery_uuid`, `session_id`, `app_version`, `device_model`, `android_version` |
| target | `addr`, `addr_type` (`public`, `random`, ...) |
| trigger advertisement | `trigger_payload_id` (references `payloads`, stored once; not counted as a sighting), `trigger_rssi`, `trigger_scanned_at`, `trigger_is_legacy`, `trigger_primary_phy`, `trigger_secondary_phy`, `trigger_adv_sid` |
| time and place | `started_at`, `ended_at`, `lat`, `lon`, `accuracy`, `location` (set by trigger) |
| result | `outcome`, `stage_reached`, `error_stage`, `error_status`, `error_name`, `disconnect_initiator`, `disconnect_status`, `mtu`, `phy_tx`, `phy_rx`, `connect_ms`, `mtu_ms`, `discover_ms`, `read_ms` |
| structure | `profile_hash` (SHA-256 hex over UUIDs and properties without values; null without services) |
| source of truth | `raw` jsonb, the full object |

`gatt_attributes` has one row per service, characteristic or descriptor in
document order: `(discovery_id, attribute_id)` is the key (ids count from 1,
parents before children), `parent_attribute_id`, `kind`, `uuid` (lowercase,
`ad_structures.uuids` notation), `is_primary` (services), `instance` (Android
instance id, not an ATT handle), `properties` (characteristics), `value` (bytea,
only when read) and `read_status` (`ok`, `insufficient_authentication`,
`insufficient_encryption`, `read_not_permitted`, `timeout`, `not_read`,
`error:<n>`; null for services). A partial discovery keeps every attribute that
was discovered; unread ones are `not_read` or carry the failure status.

Group devices by GATT structure with `profile_hash`; see "profile_hash" below.

## Ingesting scan v3 and gatt/v1

The producer contracts (paths, columns, footer keys, GATT object) are in
`docs/payload-schema-v3.md`. This is what the sync side does with them.

**Routing.** `scans/v3/YYYY/MM/DD/<uuidv7>.parquet.encrypted` and
`gatt/v1/YYYY/MM/DD/<uuidv7>.json.encrypted` follow the v2 UUID/date rules.
Everything else under `scans/v3/` or `gatt/`, and any other `<root>/v<N>/`
path, is rejected and recorded as failed. `cluetooth sync` lists `scans/` and
`gatt/` by default (`--prefix` repeats; `CLUETOOTH_PREFIX` is space-separated);
`--max-blobs` applies per prefix and overlapping prefixes are listed once.

**Scan v3 values.** The exact 18-column schema is required. Accepted ranges:
`primary_phy` 1 or 3, `secondary_phy` 0 to 3, `adv_sid` 0 to 15,
`adv_tx_power` -127 to 126, `periodic_interval` 1 to 65536, `data_status` 0 to
2 (Android's constants, stored as they are), `addr_type` 0 to 255 (0 public,
1 random). Android's in-band "absent" values are mapped to null in case a
producer sends them: `rssi` 127, `adv_sid` 255, `adv_tx_power` 127,
`periodic_interval` 0.

**Footer metadata.** `cluetooth.payload_schema = v3` and `cluetooth.payload_id`
must match the path. Every other `cluetooth.*` entry is stored in `blobs.meta`
(prefix removed, values as strings, NUL characters removed); nothing besides
those two is required, so a producer that adds keys loses nothing and one that
omits some is not rejected. Each `cluetooth.*` key may occur once. Absent
patterns can then be read against the file's capabilities (the producer's
scanner-capability keys), e.g. `select meta ->> 'device_model' from blobs where
uri = ...`.
`cluetooth.scan_session_id` fills the session and `cluetooth.app_version` fills
`blobs.app_version`. A valid empty file is accepted with its meta.

### Aggregation key

`addr_type`, `is_legacy`, `primary_phy`, `secondary_phy`, `adv_sid` and
`data_status` are part of the `sighting_minutes` key; `adv_tx_power` and
`periodic_interval` are not.

- The six key fields say which kind of advertisement was received, which the
  payload bytes cannot: the same bytes can arrive as a legacy PDU and as an
  extended PDU on 2M or Coded from one address in one minute, and different
  sets of one device carry identical data. Merging them into one row would lose
  what extended advertising adds. The earlier "first non-null wins" merge of
  these columns depended on ingest order, so a rebuild could not reproduce it;
  with them in the key every column is a sum, min, max or best sample.
- `adv_tx_power` and `periodic_interval` describe the set, not its identity.
  They merge as the maximum, which is order-independent.
- Rows of legacy and v2 sightings have null in all six, and the key treats nulls
  as equal, so their keys are unchanged. A producer that does not report a
  field (null) stays apart from one that does.
- Cost: a device that changes PDU kind within a minute gets one row per kind.
  The WiGLE export collapses them (see "WiGLE export"). `adv_shapes.minutes`
  counts distinct minutes.
- `adv_length` stays unused (it equals the payload's raw length).
- The merge joins use plain equality on `minute`, `addr` and `payload_id` and
  `is not distinct from` on the nullable key columns. A single row-wise
  `is not distinct from` comparison would force nested loops (8 s against 5 ms
  for 20k keys); `tests/test_merge_plan.py` checks the plan.

### gatt/v1

Validation is strict about types and values and tolerant about absence.
Required: `schema` (`gatt/v1`), `id` (equal to the path UUID), `target.addr`
(uppercase MAC), `started_at`, `outcome`, `stage_reached`, `services` (may be
empty). Everything else may be absent or null, but must have the documented type
and range when present: `outcome` in `complete|partial|failed|cancelled`,
`stage_reached` in `connect|mtu|discover|read|disconnect`, `mtu` 23 to 517, `phy`
1 to 3, `timings_ms` nonnegative, `location` paired, finite and in range,
timestamps ISO-8601 with an offset (stored as UTC), `ended_at` not before
`started_at`. A `value` is `{hex, status}`: `ok` needs `hex` (even-length, at
most 512 bytes, empty allowed); any other status needs `hex` null or empty; an
absent `value` is `not_read`. Unknown keys are allowed and kept in `raw`;
duplicate keys, `NaN` and `Infinity` are rejected. NUL characters are removed
from every string value (PostgreSQL text and jsonb cannot hold them; the archive
keeps the original) and a NUL in a key is rejected. `target.name` is not
stored as a column (names come from advertisements); it stays in `raw`.

**UUID notation.** Lowercase, and a Bluetooth Base UUID is shortened to 4 hex
digits (16-bit) or 8 (32-bit), which is the notation of `ad_structures.uuids`:
`180A`, `180a` and `0000180a-0000-1000-8000-00805f9b34fb` are the same service
and GATT services join advertisements.

**profile_hash.** The hex SHA-256 of the compact JSON (`separators=(",", ":")`)
of

```text
[[service_uuid, [[characteristic_uuid, [property, ...], [descriptor_uuid, ...]], ...]], ...]
```

in document order, with each characteristic's properties sorted and unique and
UUIDs normalized as above. Values, read statuses, instance ids, `primary`, MTU
and outcome are not part of it, so a partial and a complete discovery of the
same device, or two devices with the same GATT database, hash equally. It is
null when no service was discovered.

### WiGLE export

WiGLE stores little per observation, so the export is one CSV row per address
per minute, whatever the payloads, sessions, uploaders or PDU kinds behind it
(each is its own `sighting_minutes` row).

- **Row content.** Position, accuracy, RSSI and time come from the best located
  minute row of the address-minute, ranked as the minute table ranks its best
  samples (`best_score` descending, then smaller accuracy, then earlier time).
  The name is the most recent non-empty `local_name` among all of its rows
  (latest `last_seen`), even from a row without a position. `MfgrId` is the first
  manufacturer id of the chosen row's payload. `AuthMode` (the capabilities
  string, `Misc [LE]` by default) and `Type` (`BLE`) are unchanged.
- **Nothing uploaded twice.** A batch claims the address-minute: it records
  *every* minute row of it in `wigle_upload_batch_minutes`, and an address-minute
  is pending only while none of its rows belongs to an active batch (`created`,
  `exported`, `uploading`, `uploaded`, `completed`). A row merged in later (another
  payload, another PDU kind, even a better fix) therefore does not bring the
  address-minute back, the same as a late sighting merged into an uploaded minute
  row did before. A `failed` batch releases its address-minutes. Batch
  `row_count` is the number of CSV rows (address-minutes); `min_item_id` and
  `max_item_id` are over member minute-row ids. Membership written for the older
  minute-row model stays valid: any claimed row claims its address-minute, so a
  sibling row that was still pending at the switch is not exported separately.
- **Creation is serialized.** Row locks cannot guard a group, so batch creation
  takes a transaction-level advisory lock (4381022502).

### Concurrency

One transaction per object, but Polars' ADBC `write_database` commits the
session's transaction, which releases the ledger row lock and the
transaction-level advisory locks. Every ingest therefore takes a session-level
advisory lock on the blob (`pg_advisory_lock(1, hashtext(uri))`, held until the
connection closes) before it reads `blobs.success`: a second process waits, then
sees the first one's result and skips the object. `blobs/mark_failed.sql` never
overwrites `success = true`. The transaction-level shared lock that makes
`cluetooth rebuild` wait is still released by that commit (known issue, not
changed here).

## Write paths

All three share `pipeline/sightings.py`: distinct raws → `raws` temp table →
structures and payloads → `batch` temp table → merge into `sighting_minutes`.

GATT objects use the same ledger and payload dictionary but their own
transaction (`pipeline/gatt_ingest.py`): the trigger payload, the discovery, its
attributes and `success = true` commit together.

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
