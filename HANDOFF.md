# Handoff: ops/continuous-sync (2026-10-25)

Branch `ops/continuous-sync`, worktree `/home/wyb/.paseo/worktrees/1pefufa6/sync`.
Commits: `196b125` (robust continuous sync, tests passed then), `c805203` (WIP
schema restructure), `edc03f2` (backfill + replay/rebuild CLI), `e989b7b` and
`4f49915` (minute-schema tests), `455f7e8` (schema.sql), `733fb83` and
`365c0d9` (docs). Nothing is pushed.

## Report, 2026-10-26 (this pass)

### What passed

- `mise run //cluetooth-sync:test`: **105 passed** (including the Rust
  SealedBox interop test). `ruff check`, `ruff format --check` and
  `basedpyright` are clean.
- The backfill and every verification check pass on a freshly migrated database
  in the tests, and on the 1.2M-row synthetic dataset in the scratch container:
  checks 01–11 `ok`, 12/13 informational, `backfill rows=1200002`.
- Migration `20261026000000` applies in ~0.1 s, and its down migration still
  restores the old schema (re-verified on the scratch container).
- All work is local: throwaway containers `cluetooth-schema-scratch`
  (+ `scratch_old`, `scratch_schema` inside it) and testcontainers. simi, GCS
  and Firebase were never touched.

### Measurements (1.2M synthetic sightings, 12,002 distinct raws, 12,542
(addr,raw) keys, 62-byte raws with zero padding, 3 uploaders/144 objects)

| | old schema | new schema |
|---|---|---|
| rows | 1,200,002 in `scans` | 66,862 minute rows + 12,602 payloads + 2,671 structures |
| size | 407 MB (`scans` 384 MB: 283 heap + 101 indexes) | **53 MB** (incl. partitions/indexes) |
| rollups | `advs` 12,542 rows/10.0 MB (2.8 s), `adv_enrichments` 12,542 rows/7.7 MB | `adv_shapes` is a view, no enrichment rows |
| WiGLE | `wigle_upload_batch_scans` 60,000 rows/5.8 MB | `wigle_upload_batch_minutes` 60,000 rows/6.8 MB |

- Dedupe: 3,600,006 structure instances in sightings → 2,671 `ad_structures`
  rows (1,348x); 37,806 structure slots in payloads → 2,671 (14.2x);
  1,200,002 sightings → 12,602 payloads (95x).
- Minute rows: 66,862 per `(minute, addr, payload)` vs 60,002 per
  `(minute, addr)` — the payload dimension adds 11.4%.
- Backfill: **169.1 s for 1,200,002 rows = 140.9 s per million rows** (61 id
  ranges of ≤100k rows at `--batch-size 100000`; 13 of them held data).
- Verification: 154 s total; 145 s of that is check 12 (informational
  local-name divergence, 240,000 rows); check 08 5.3 s; every numeric check
  under 1.5 s.
- Ingest of one 20,000-sighting object (200 devices): decrypt+parse 0.06 s
  (312k rows/s), insert 0.49 s (40.6k rows/s), end-to-end 0.56 s
  (35.9k rows/s) writing 12,000 minute rows. The old per-sighting path inserted
  the same object in 0.25 s (80k rows/s, 20,000 rows), so the new path costs
  ~2x per ingested row but writes 40% of the rows.
- `replay` of that object: 0.06 s (decrypt + Zstd + parse, 20,000 rows).
- Prod reference (read-only, 1M contiguous scans): 48,229 per-addr minute rows,
  14,334 distinct raws, 18,172 (addr,raw) keys. The synthetic 1M slice gives
  50,002 / 10,011 / 10,451 — minute-row density matches, payload variety is
  lower than prod's.
- Estimate for 12.26M rows: backfill ≈ 29 min, verification ≈ 26 min
  (dominated by the informational check), i.e. **under an hour**, plus a fresh
  dump first. Disk after dropping `*_old`: ≈ 540 MB instead of ≈ 4.2 GB.

### Open questions for the owner

1. `scans_old.blob` is nullable. `backfill/batch.sql` joins `blobs`, so a row
   with a null or missing blob is dropped and the per-range count check then
   raises. Should the backfill (a) abort, or (b) skip such rows with a warning
   and report the count? I did not check prod for null blobs (local only).
2. `sighting_minutes` only enforces the lat/lon *ranges* of its extent columns.
   The old per-row coordinate contract (paired lat/lon, accuracy requires
   coordinates and is finite) is now enforced in Python for v2 payloads only.
   Should legacy JSONL ingest validate the same, or the table get an
   equivalent check?
3. The `cluetooth backfill` CLI exits nonzero when a check fails. Confirm that
   is the wanted behaviour for the runbook.
4. `sightings/delete_empty.sql` now keeps a zeroed minute row that a WiGLE batch
   already claims (the FK is `on delete restrict`); such a row exports an empty
   CSV line. Acceptable, or should rebuild refuse instead?
5. Names come from `raw`, so the producer's masked names are unmasked in
   `payload_fields` and in WiGLE SSIDs (240,000 divergent rows in the synthetic
   set). Confirm this is still acceptable.
6. The v3 columns (`addr_type`, `adv_length`, `is_legacy`, `primary_phy`,
   `secondary_phy`) have no producer yet and no test coverage; only the merge
   rule (first non-null) is implemented.
7. When may `*_old` and `backfill_progress` be dropped, and when may
   `adv_shapes` become materialized?

## Hard rules from the owner

- **Local only.** No deploy, migration, service start or writes on simi.
  Leave simi exactly as it is. Read-only SELECTs were allowed for the survey.
- Never read or print secrets. Do not push to GitHub. Do not rewrite the sync
  in another language.
- If something is destructive or ambiguous, stop and report.

## State of simi (unchanged since the survey)

- I started the existing `cluetooth-db` container. It had exited cleanly after
  a host reboot and has no restart policy.
- Backup: `/data/newcluetooth/backups/cluetooth-predeploy-20261025.dump`
  - 418 MB, `pg_dump -Fc`, `.sha256` alongside, TOC verified.
- Survey image `cluetooth-sync:survey-20261025`; build dir
  `/data/newcluetooth/build-20261025`.
- The checkout is still `4220a1b`, and no migrations were applied after
  `20260702120000`.
- Root fs is 100% full; `/data` and the docker volume have plenty of space.
- Pending in GCS:
  - 29 new legacy flat objects plus 1 failed (invalid hex);
  - 22 `scans/v2/` objects;
  - 6 historical `scans/schema=v2/` objects (stay rejected).

## Agreed final design (owner decisions, in order of precedence)

1. **No per-sighting rows in the DB.** The GCS objects (legacy JSONL, v2
   Parquet) are the raw archive and must never be deleted. Raw data is read
   back via `replay`; aggregates are regenerated via `rebuild`.
2. **`ad_structures`** stores each distinct AD structure once, unique on
   `(ad_type, data)`, with generated `local_name`, `company_id` and `uuids`.
   Matching rules use this table.
3. **`payloads` is thin:**
   - `sha256` (exact identity), ordered `structure_ids`, `zero_padding`, and
     `tail` (unparsed non-zero remainder, normally null);
   - stats: `first_seen`, `last_seen`, `sightings`;
   - no raw column. `payload_raw(id)` rebuilds the exact bytes.
   - Prod check: the remainder after the last structure is always zero bytes.
   - Ingest raises if any payload fails the round-trip
     (`payloads/verify_raws.sql`).
4. **`uploaders`;** `blobs` gains `id`, `session_id` and `uploader_id`, and
   keeps `meta`, `app_version`, `attempts` and `next_attempt_at`.
5. **`sighting_minutes`**, partitioned by month on `minute`:
   - **Key:** `(minute, addr, payload_id, uploader_id, session_id)`, unique
     with nulls not distinct. The session is added because v2 objects have no
     uploader identity. One payload per row.
   - **Columns:** `blob_counts` jsonb provenance (+ generated `blob_ids`),
     sightings, first/last seen, rssi min/max/sum/count/mean, spatial extent
     (min/max lat/lon of all positions), tx_power (max), is_connectable (any),
     and the v3 fields (`addr_type`, `adv_length`, `is_legacy`,
     `primary_phy`, `secondary_phy`; first non-null wins).
   - **Indexes:** `(addr, payload_id)` and GiST on `location`, plus the key
     and `(id, minute)` PK used by WiGLE.
   - **Location = best sample:**
     - score = `rssi/20 − 2·log10(accuracy_m)`;
     - accuracy defaults to 50 m when missing and is floored at 1 m;
     - ties go to the better accuracy, then the earlier time;
     - stores `location` (geography), `best_rssi`, `best_accuracy`,
       `best_score` and `best_seen`;
     - merges keep the better sample (`sighting_sample_better`).
     - The coefficients are **provisional**, to be tuned against bought-device
       ground truth.
   - **Exactness:** merges across blobs are exact (sums, min/max, best
     sample).
6. **No `advs` table and no incremental rollup.** `adv_shapes` is a plain view
   over `sighting_minutes` plus the `payload_fields` view. Later, if needed,
   it becomes a materialized view.
7. Spatial queries (e.g. future OSM data in an `osm` schema) target
   `sighting_minutes` only.
8. **Old tables are renamed, never dropped**, until the owner says so:
   `scans_old`, `advs_old`, `adv_enrichments_old`,
   `wigle_upload_batch_scans_old`, `projection_state_old`.
9. **Backfill:**
   - aggregates `scans_old` into minute rows in batched, resumable id ranges;
   - verifies totals per blob, per addr and per payload, the payload
     round-trip and WiGLE membership.
10. **WiGLE** exports one row per minute (best sample), with membership in
    `wigle_upload_batch_minutes`. The backfill maps old uploaded scans to
    their minute rows, so nothing is uploaded twice.

## Done

- `196b125`, robust sync:
  - per-blob backoff (migration `20261025093000`);
  - cycle-error backoff;
  - heartbeat and `cluetooth health`, plus the compose healthcheck;
  - `--list-only` survey;
  - double-ingest guard;
  - `docs/operations.md` (describes the *old* schema).
- `c805203`, WIP:
  - Migration `db/db/migrations/20261026000000_minute_index_schema.sql`:
    up/down/up verified in a scratch container. Tokenizer, dedupe,
    round-trip, `payload_fields` and the score functions were spot-checked
    by hand.
  - `pipeline/sightings.py`: shared write path (raws → payloads → batch →
    merge).
  - `pipeline/ingest.py`: rewritten. It parses the uploader and app version
    from legacy names, and takes the v2 `cluetooth.scan_session_id` footer
    into `session_id`.
  - `pipeline/replay.py`: `replay_sightings` and `rebuild_minutes`.
  - `wigle.py`: ported to minute rows (uses `min_item_id`/`max_item_id`).
  - SQL under `cluetooth_sync/queries/`: `payloads/`, `sightings/`,
    `backfill/`, `backfill/verify/01..13`.
  - Removed: `project.py`, `enrich.py`, the projection thread and the
    projection health fields.
- `edc03f2`, backfill and CLI:
  - `pipeline/backfill.py`: `run_backfill`/`backfill_minutes` (progress table,
    `blobs.sql`, partitions, batched id ranges, enrichments) and
    `verify_backfill` over `queries/backfill/verify/*.sql` with per-check
    timing; `queries.query_names` enumerates them.
  - `sightings.months_between`, shared with `rebuild`.
  - CLI `backfill`, `replay` (ndjson/csv/parquet, `--blob` or
    `--uploader/--since/--until`) and `rebuild`.
  - `sightings/delete_empty.sql` keeps minute rows a WiGLE batch already claims.
- `e989b7b`, `4f49915`, tests:
  - `test_pipeline.py`, `test_continuous_sync.py`, `test_wigle.py`,
    `test_payload_v2.py` rewritten for the minute schema; the projection,
    watermark and builtin-enrichment tests are gone with the code.
  - New `test_sightings.py` (merge exactness in both orders vs a single pass,
    structure/payload dedupe, `payload_raw` round-trip with padding and a
    truncated tail, partition routing and the default partition, extent
    check), `test_replay.py` (encrypted replay, CLI replay, rebuild keeps ids
    and follows the archive), `test_backfill.py` (batched backfill, resume,
    every check, failure detection, masked-name info check).
  - `conftest.clean_database_url`: a freshly migrated database per test for
    whole-table assertions.
  - Fixed on the way: ADBC cannot bind a null parameter (absent
    uploader/app-version/session now travel as `-1`/`''` sentinels);
    `ble.parse_raw` now stops at a truncated field like `ble_adv_split`;
    `write_wigle_csv` without `--limit` used to pass a null parameter and
    failed.
- `455f7e8`, `db/schema.sql`: cleaned `pg_dump --schema-only` snapshot.
- Docs: `docs/schema.md` (new), `docs/operations.md` (new runbook marked NOT
  EXECUTED, GCS archive rule, lifecycle check, spatial queries, provisional
  coefficients, replay/rebuild), `AGENTS.md` and `cluetooth-sync/README.md`.

## Remaining after this pass

The seven steps below are done. What is left is the owner's: answering the open
questions above, and then the simi runbook in `docs/operations.md`
(**NOT EXECUTED**) — stop the sync, fresh dump, migrate, backfill, verify,
start the sync. Also still open from the survey: the 29 pending legacy objects
(one invalid hex), the 22 `scans/v2/` objects and the 6 rejected
`scans/schema=v2/` objects.

### The original step list (kept for reference)

1. **Backfill driver** `pipeline/backfill.py` plus the CLI command
   `cluetooth backfill [--batch-size 100000] [--verify-only]`:
   - create `backfill_progress`;
   - run `backfill/blobs.sql`;
   - ensure partitions for the months in `backfill/source_range.sql`;
   - loop id ranges. Each range is one transaction:
     - create raws; `collect_raws.sql`; `store_payloads`;
     - create batch; `batch.sql`; check the count against `count_range.sql`;
     - `merge_batch(update_payload_stats=True)`;
     - `wigle_membership.sql`;
     - `progress_set`;
   - then run `enrichments.sql`;
   - then run every `verify/*.sql` (`ok` = `old_value == new_value`, except
     `info_*`) and print timing per million rows.
   - Run verification before any new ingest: checks 01/02/05/07 assume the
     new tables hold only backfilled data.
2. **CLI:** add `replay` (`--blob URI` or `--uploader --since --until`;
   output ndjson/parquet/csv) and `rebuild` (`--since --until [--uploader]`).
   Both use `_build_storage_client` and `_read_private_key`.
3. **Tests** (testcontainers):
   - Fix the existing tests that query `scans`/`advs`/`adv_enrichments`/
     `project_advs`/`insert_builtin_ad_structures`: `test_pipeline.py`,
     `test_continuous_sync.py`, `test_wigle.py` (still uses `scan_id`,
     `_insert_scan`, `wigle_upload_batch_scans`), `test_payload_v2.py`,
     `test_rust_encryption_compatibility.py` and `test_pipeline_stages.py`.
   - Add tests for:
     - legacy and v2 ingest into minutes;
     - structure and payload dedupe;
     - raw round-trip, including padding and a truncated tail;
     - partition routing and the default partition;
     - a minute split across two blobs merging exactly, including the best
       sample;
     - replay of an encrypted blob (PyNaCl SealedBox keypair);
     - rebuild giving identical rows with ids kept;
     - backfill + verify on rows inserted into `scans_old`;
     - WiGLE on minutes.
4. **Measure** in the throwaway container `cluetooth-schema-scratch`
   (postgis 18-3.6, port 55499, user/db `scratch`; still running, remove when
   done):
   - Migrate to `20261025093000`, then fill the old `scans` with fixtures plus
     a synthetic batch of ~1–2M rows (62-byte raws; payload state changing
     every few minutes).
   - Populate `advs` with main's old `advs/project.sql`
     (`git show 9421939:cluetooth-sync/src/cluetooth_sync/queries/advs/project.sql`)
     and the old builtin enrichment SQL, and add WiGLE batch rows.
   - Migrate up, run the backfill and verify.
   - Report:
     - rows and index sizes, old vs new;
     - structure- and payload-level dedupe ratios;
     - per-(addr,payload) minute rows vs per-addr minute rows;
     - ingest speed and replay time;
     - an estimate for 12.26M rows.
   - Prod reference (read-only, 1M contiguous scans): 48,229 per-addr
     minute rows, 14,334 distinct raw, 18,172 (addr,raw) keys.
5. **Cleaned schema:** generate `db/db/schema.sql` with
   `pg_dump --schema-only` from the scratch DB, cleaned of SET/owner lines.
   kb/lab has its own `db/schema.sql`, so expect a merge conflict.
6. **Docs:**
   - `docs/operations.md`:
     - the migration runbook (marked NOT EXECUTED);
     - GCS objects must never be deleted (lifecycle may only move them to
       colder storage);
     - morning check: confirm the bucket has no delete lifecycle rule;
     - spatial queries target `sighting_minutes`;
     - the best-sample coefficients are provisional;
     - replay/rebuild usage.
   - A schema doc.
   - Update `AGENTS.md` (still describes `scans`/`advs` and matviews) and
     `cluetooth-sync/README.md`.
7. Commit locally. Report to the owner.

## How to run tests

```sh
cd cluetooth-sync
uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked basedpyright
uv run --locked pytest -q --deselect tests/test_rust_encryption_compatibility.py   # fast
cd .. && mise run //cluetooth-sync:test   # full, builds the Rust interop example
```

Tests use testcontainers (`postgis/postgis:18-3.6`) and mise's `dbmate`. Do not
use the shared `cluetooth-test-db`: the KB site uses it.

## Traps

- **ADBC fetching.** `cursor.fetchone()`/`fetchall()` need pyarrow, which is
  not installed. Use `cursor.fetch_polars()`.
- **Lock queries return `void`.** Lock statements are written as
  `select true as locked from pg_advisory_xact_lock...`.
- **Ingest vs rebuild lock.** Ingest takes the shared advisory lock
  4381022501 and `rebuild` takes it exclusively. Rebuild must create
  partitions *before* taking the lock, or it self-deadlocks.
- **Partition boundaries.** `ensure_sighting_partition` uses UTC bounds. If
  the default partition already holds rows for a month, that month's
  partition can't be created, and the function returns false.
- **Null parameters.** ADBC cannot bind a null parameter at all
  (`nanoarrow ... Expected array with 0 buffer(s) but found 1 buffer(s)`). The
  rebuild queries pass `''` for "all uploaders"; ingest sends `-1` (uploader) or
  `''` (app version, session) and the SQL restores the null with `nullif`;
  `write_wigle_csv` sends `0` for "no limit". Tests cover the null-uploader and
  no-limit paths.
- **Unmasked names.** Prod `scans.local_name` is masked for some names
  (`QT 325D****`) while `raw` holds the full name. The new schema derives
  names from raw, which unmasks them, and WiGLE SSIDs would carry the full
  name. Flag this to the owner. Verify check 12 counts it.
- **v2 uploader identity.** v2 objects have no uploader identity; without a
  session footer, different phones' v2 sightings in the same minute merge
  into one row.
- **kb/lab dependents.** The research/wiki tooling lives on branch `kb/lab`
  (another worktree in use, not edited): `cluetooth-agents/kb/src/kb/corpus.py`
  (reads an advs mirror: `raw_hex`, `scans_count`),
  `cluetooth-agents/kb/tests/conftest.py`, `db/schema.sql`. They need a
  corpus query over `sighting_minutes` + `payload_raw`. The wiki pages
  `carel-hvac.md` and `levoit-core-400s.md` only cite past `adv_shapes`
  queries.
- **Migration on simi (later).** Starting the compose `sync-continuous`
  service runs `migrate` automatically, which applies all pending
  migrations. Take a fresh dump first, and stop the sync during the
  backfill.
- **Raw bytes.** `raw` is 62 bytes (31 AD + 31 scan response, zero padded).
  `parse_ble_adv` stops at the first zero length, and so do
  `ble_adv_split` and `ble.parse_raw`; the rest goes to
  `zero_padding`/`tail`.
- **Scratch containers.** The measurements used `cluetooth-schema-scratch`
  (postgis 18-3.6, port 55499, user/db `scratch`, plus databases
  `scratch_old` at `20261025093000` and `scratch_schema` for the dump). The
  generator and timing scripts are `/tmp/gen_old.sql`, `/tmp/old_state.sql`,
  `/tmp/new_state.sql`, `/tmp/measure_ingest.py` (outside the repo). Remove the
  container when the numbers are accepted; nothing else depends on it.
- **Host disk.** The host root filesystem is ~98% full (26 GB free); the
  synthetic dataset needs well under 1 GB, but keep dumps on `/data`.
