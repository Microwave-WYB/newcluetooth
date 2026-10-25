# Handoff: ops/continuous-sync (2026-10-25)

Branch `ops/continuous-sync`, worktree `/home/wyb/.paseo/worktrees/1pefufa6/sync`.
Commits: `196b125` (robust continuous sync, tests passed then) and `c805203` (WIP
schema restructure, **tests not updated, suite will fail**). Nothing is pushed.

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

## Remaining, step by step

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
- **Null parameters.** Null typed parameters through ADBC are untested. The
  rebuild queries pass `''` for "all uploaders" instead of null.
  `ensure_ingest.sql` passes possibly-null uploader/session ids; check this
  in tests.
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
  `parse_ble_adv` stops at the first zero length, and so does
  `ble_adv_split`; the rest goes to `zero_padding`/`tail`.
