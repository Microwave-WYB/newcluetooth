# Production sync operations (simi)

> **Status, 2026-10-26:** the robust continuous sync and the minute-index schema
> restructure are implemented and tested on branch `ops/continuous-sync`, but
> **not deployed**. On simi, the checkout is still `4220a1b`, no migrations
> after `20260702120000` have been applied, `sync-continuous` is not running,
> and the backfill has never run. The steps below are the intended procedure;
> the restructure runbook is marked **NOT EXECUTED**.
>
> See `docs/schema.md` for the schema itself and `HANDOFF.md` for the local
> measurements (1.2M synthetic sightings) and open questions.

## Where things are

- Host: `ssh simi` (jumps through trolley).
- Compose project `newcluetooth`: `/home/y2wei/Projects/newcluetooth`.
- Database container: `cluetooth-db`. Data is at `/data/newcluetooth/postgres-data`.
- Blob mirror: `/data/newcluetooth/gcs-mirror`, a symlink to `/data/cluetooth/downloads`.
- Secrets: compose secrets from `./secrets/`, mounted only into sync containers.
  Never print or copy them.
- Disk: `/` on simi is nearly full (100% on 2026-10-25). Keep backups and
  build contexts on `/data`.

## Backup

- Pre-deploy full dump (2026-10-25):
  `/data/newcluetooth/backups/cluetooth-predeploy-20261025.dump`
  - format: `pg_dump -Fc -Z 6`
  - size: 418 MB, from a 6.5 GB database
  - checksum: in `.dump.sha256` alongside the dump
- Take a new dump before any deploy that applies migrations:

  ```sh
  cd /data/newcluetooth/backups
  df -h /data
  F=cluetooth-$(date -u +%Y%m%dT%H%MZ).dump
  docker exec cluetooth-db pg_dump -U cluetooth -d cluetooth -Fc -Z 6 > $F.partial \
    && mv $F.partial $F && sha256sum $F > $F.sha256 && ls -la $F
  docker exec -i cluetooth-db pg_restore --list < $F | grep -c "TABLE DATA"
  ```

- Restore (destructive to the target DB, so confirm first): `pg_restore --clean
  --if-exists -U cluetooth -d cluetooth < FILE` via `docker exec -i cluetooth-db`.

## The GCS objects are the raw archive

Nothing in this branch deletes, moves or rewrites stored objects. After the
restructure the database keeps only minute aggregates, so **the objects are the
only copy of the raw sightings**:

- Never delete objects, and never let a bucket lifecycle rule delete them.
  Moving them to colder storage classes is fine and is the only allowed
  lifecycle action.
- Morning check that the bucket has no delete rule (read-only):

  ```sh
  gcloud storage buckets describe gs://BUCKET --format='value(lifecycle_config)'
  # or, if a lifecycle file is maintained:
  gcloud storage buckets describe gs://BUCKET --format=json | jq .lifecycle
  ```

  Any rule whose `action.type` is `Delete` on `scans/` (or on the whole bucket)
  must be removed before the restructure is deployed. `SetStorageClass` and
  `AbortIncompleteMultipartUpload` rules are fine.
- Raw data is read back with `cluetooth replay` and aggregates are regenerated
  with `cluetooth rebuild` (see `docs/schema.md`).

## Spatial queries

Query `sighting_minutes` for anything spatial (future OSM data lives in an
`osm` schema and joins it): `location` is the minute's best sample and the
`min_lat`/`max_lat`/`min_lon`/`max_lon` extent flags co-moving devices. The
per-`(addr, payload)` view `adv_shapes` is not indexed and should be filtered or
joined against a small candidate set rather than scanned whole.

The best-sample score (`rssi/20 - 2*log10(accuracy_m)`) is **provisional**: the
coefficients are to be tuned against bought-device ground truth.


## How the continuous sync runs

`sync-continuous` runs `cluetooth-sync` with `CLUETOOTH_POLL_INTERVAL_SECONDS` set:

- **Cycles.** Each cycle performs a full GCS listing of `scans/`, skips blobs
  that are `success = true` or still inside their retry backoff, and then
  downloads and ingests the rest with bounded worker pools.
- **Per-blob failures.** A failed download, a routing rejection, or a decrypt or
  insert failure is recorded in `blobs` (`success = false`, `error`,
  `attempts`, `next_attempt_at`). The cycle continues. Retry delay doubles from
  1 minute up to 1 hour.
- **Cycle failures.** If the DB or GCS is unreachable for a whole cycle, the
  loop logs the error, backs off (up to 10 minutes), and tries again. The
  process does not exit.
- **Restart safety.** Each blob is one transaction: payloads, minute merge and
  `success = true` commit together. A kill mid-blob rolls back, and the blob is
  retried. A row lock on the ledger entry keeps two sync processes from
  inserting the same blob.
- **Formats.**
  - Legacy flat `scans/<ts>_<id>_0.0.1–0.0.4.jsonl.zst.encrypted` and
    `scans/v2/YYYY/MM/DD/<uuidv7>.parquet.encrypted` are ingested.
  - Historical `scans/schema=v2/...` objects are rejected before download. They
    are recorded as failed and left untouched in GCS. Their backoff caps at one
    retry per hour.
- **Storage.** A cycle writes each blob's distinct advertisements into
  `ad_structures`/`payloads` (once each) and merges one row per
  `(minute, addr, payload, uploader, session)` into `sighting_minutes`. There is
  no projection thread and no materialized view to refresh: enrichment is
  computed by `payload_fields`/`ad_structures` at query time.
- **Logs.** `docker compose logs -f sync-continuous` shows these lines:
  - `discover N pending blobs`
  - `progress done/N ok= failed= rows= rate= eta=` every 30 s
  - `cycle K done ...`
  - `failed <uri>: <reason>` on stderr

Recommended worker settings on the shared DB host: `CLUETOOTH_DOWNLOAD_WORKERS=4`,
`CLUETOOTH_INGEST_WORKERS=2`, `CLUETOOTH_QUEUE_SIZE=16`. The current simi `.env`
sets 24/16/256, which is too heavy for a server that hosts other work.

## Health

- The heartbeat file is `/tmp/cluetooth-sync/heartbeat.json` inside the
  container, rewritten every 15 s. It holds the phase, the current cycle's
  counts, total ok/failed/rows, consecutive cycle error counts and the last
  cycle error.
- The compose healthcheck runs `cluetooth health --max-age-seconds 120`. It is
  unhealthy if the heartbeat is stale or there are 5 or more consecutive cycle
  errors.
- Manual checks:

  ```sh
  docker compose ps sync-continuous                 # (healthy)
  docker compose exec sync-continuous cluetooth health
  docker compose exec sync-continuous cat /tmp/cluetooth-sync/heartbeat.json
  docker exec cluetooth-db psql -U cluetooth -d cluetooth -c \
    "select success, count(*), max(synced_at) from blobs group by 1"
  docker exec cluetooth-db psql -U cluetooth -d cluetooth -c \
    "select left(error, 80), count(*), max(attempts) from blobs
     where success is false group by 1 order by 2 desc"
  ```

- List-only inventory (no download, no writes; the DB session is read-only).
  This needs an image built from this branch:

  ```sh
  docker compose run --rm --no-deps -e PGOPTIONS='-c default_transaction_read_only=on' \
    sync --list-only
  ```

## Restructure runbook — **NOT EXECUTED**

The minute-index schema is applied by migration `20261026000000`, which renames
the old tables instead of dropping them. Applying it alone leaves an **empty**
minute index: `cluetooth backfill` must copy `scans_old` into it before the
sync runs again. Stop the sync for the whole sequence so verification compares
backfilled data only.

0. Confirm the bucket has no delete lifecycle rule (above). Nothing else in
   this runbook can recover raw data.
1. Take a fresh dump (above) and check `df -h /data /var/lib/docker`.
2. `docker compose stop sync-continuous` and confirm it stayed stopped.
3. **Pre-migration integrity check.** `raw` is authoritative and
   `scans.local_name` was modified in place at some point, so before copying
   `scans_old` into the minute index, replay a random sample of the archived
   objects and compare their raw bytes per sighting with `scans_old`:

   ```sh
   docker compose run --rm --no-deps --entrypoint cluetooth sync-continuous \
     verify-raws --database-url postgresql://cluetooth:infra_wireless_scanning@postgres:5432/cluetooth?sslmode=disable \
     --private-key /run/secrets/cluetooth-private-key --sample 10
   ```

   It prints one line per sampled object
   (`archived=<sightings in the object> scans_old=<rows for that blob>
   mismatched=<keys whose multiplicity differs>`) plus a summary, and exits
   nonzero if any object disagrees or cannot be routed. A mismatch means the
   archive and the ledger have diverged: stop and report it to the owner
   instead of continuing, because the backfill copies `scans_old`, not the
   objects. A larger `--sample` costs one GCS read per object; 10 is enough to
   catch a systematic divergence, more is better if the run allows it.
4. Apply the migration: `docker compose run --rm migrate` (dbmate `up`). This
   runs `20260805233000` and `20261025093000` first if they are still pending,
   then `20261026000000_minute_index_schema`. The rename itself is metadata
   only; the new tables start empty.
5. Run the backfill inside the sync image (it needs no GCS access). The image
   entrypoint is `cluetooth-sync`, so override it:

   ```sh
   docker compose run --rm --no-deps --entrypoint cluetooth sync-continuous \
     backfill --database-url postgresql://cluetooth:infra_wireless_scanning@postgres:5432/cluetooth?sslmode=disable \
     --batch-size 100000
   ```

   It is resumable: a re-run continues at `backfill_progress.through_id`, and
   `--verify-only` re-runs the checks without aggregating. The command exits
   nonzero when a check fails; report that to the owner instead of continuing.
   The same entrypoint override runs `replay` and `rebuild`.
6. Read the verification output: checks 01–11 must be `ok` and check 12
   (`12_info_default_partition_rows`) is informational.
7. Only then start the sync: `docker compose up -d sync-continuous`.
8. Watch `docker compose logs -f sync-continuous`, `docker compose ps` and
   `cluetooth health`.

Local measurements of this sequence (1.2M synthetic sightings): backfill
aggregation 14.9 s (12.4 s per million rows), verification 10.1 s, and the
pre-migration check costs one GCS read, one decrypt/parse and one query per
sampled object. The new tables are 47 MB where the old ones were 407 MB. See
`HANDOFF.md`.

## Deploy (intended, on hold)

1. Take a backup (above) and check `df -h /data /var/lib/docker`.
2. `git fetch` from the workstation remote, or apply the branch bundle, then
   `git checkout ops/continuous-sync` in the simi checkout.
3. Lower worker counts in `.env` (see above). Keep a copy:
   `cp .env .env.bak-YYYYMMDD`.
4. `docker compose build sync-continuous`.
5. `docker compose up -d sync-continuous`. Compose first runs `migrate`
   (dbmate `up`). The migrations it applies on top of `4220a1b`:
   - `20260805233000_coordinate_contract_checks`: `NOT VALID` checks. These
     take a brief `ACCESS EXCLUSIVE` lock on `scans`/`gatt_discoveries`, with
     no table scan.
   - `20261025093000_blob_retry_backoff`: adds 2 columns to `blobs`
     (metadata-only).
   - `20261026000000_minute_index_schema`: renames the old tables and creates
     the minute index. **Do not apply it this way on simi** — use the
     restructure runbook above, which stops the sync and runs the backfill in
     between.
6. Watch `docker compose logs -f sync-continuous` and `docker compose ps`.

## Restart / stop

- Restart: `docker compose restart sync-continuous`. This is safe mid-backlog.
- Stop: `docker compose stop sync-continuous`. `restart: unless-stopped` keeps
  it stopped across reboots until it is started again.
- `cluetooth-db` has no restart policy. After a host reboot it must be started
  with `docker start cluetooth-db`, as happened on 2026-10-25.

## Roll back

1. `docker compose stop sync-continuous`.
2. `git checkout 4220a1b` (the previous deploy) and rebuild the image.
3. The additive migrations (`20260805233000`, `20261025093000`) can stay. To
   remove them:

   ```sh
   docker compose run --rm migrate --url ... --migrations-dir /db/migrations down
   ```

   Run it once per migration, newest first.
4. Rolling back `20261026000000` restores the old table names and **discards
   everything written to the minute tables** (`sighting_minutes`, `payloads`,
   `ad_structures`, `uploaders`, `wigle_upload_batch_minutes`). Ingest writes to
   `scans_old` again after the rollback, so a later re-apply needs a re-backfill;
   `backfill_progress` would have to be dropped first. Confirm with the owner
   before rolling this one back.
5. Restore the dump only if data was damaged. Confirm with the owner first.

## Pending inventory (survey, 2026-10-25)

| kind | objects | succeeded | failed | new |
|---|---|---|---|---|
| legacy flat | 10050 | 10020 | 1 (invalid hex `g` in raw) | 29 (Aug 17, Sep 5, Oct 7) |
| `scans/v2/` | 22 | 0 | 0 | 22 (all Aug 2026) |
| `scans/schema=v2/` | 6 | 0 | 0 | 6 (Aug 2026, will be rejected) |

DB at survey time: 6.5 GB, `scans` 12.26M rows, last scan 2026-08-01.
