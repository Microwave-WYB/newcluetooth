# Production sync operations (simi)

> **Status, 2026-10-25:** the robust continuous sync is implemented and tested
> on branch `ops/continuous-sync`, but **not deployed**. The owner put the
> deploy on hold while a scan-schema restructure is agreed. On simi, the checkout
> is still `4220a1b`, no new migrations have been applied, and `sync-continuous`
> is not running. The steps below are the intended procedure.

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
- **Restart safety.** Each blob is one transaction: scans insert plus
  `success = true`. A kill mid-blob rolls back, and the blob is retried. A row
  lock on the ledger entry keeps two sync processes from inserting the same blob.
- **Formats.**
  - Legacy flat `scans/<ts>_<id>_0.0.1–0.0.4.jsonl.zst.encrypted` and
    `scans/v2/YYYY/MM/DD/<uuidv7>.parquet.encrypted` are ingested.
  - Historical `scans/schema=v2/...` objects are rejected before download. They
    are recorded as failed and left untouched in GCS. Their backoff caps at one
    retry per hour.
- **Rollups.** A background thread runs the incremental `advs` projection and
  the built-in AD-structure enrichment every
  `CLUETOOTH_PROJECT_INTERVAL_SECONDS` (default 600).
  - There are no materialized views to refresh.
  - The projection reads a commit-safe watermark: ingest transactions hold a
    shared advisory lock, and the projection takes it exclusively just long
    enough to read `max(scans.id)`. Ingest pauses for at most the duration of
    one in-flight blob insert.
- **Logs.** `docker compose logs -f sync-continuous` shows these lines:
  - `discover N pending blobs`
  - `progress done/N ok= failed= rows= rate= eta=` every 30 s
  - `cycle K done ...`
  - `project advs through scan ...`
  - `failed <uri>: <reason>` on stderr

Recommended worker settings on the shared DB host: `CLUETOOTH_DOWNLOAD_WORKERS=4`,
`CLUETOOTH_INGEST_WORKERS=2`, `CLUETOOTH_QUEUE_SIZE=16`. The current simi `.env`
sets 24/16/256, which is too heavy for a server that hosts other work.

## Health

- The heartbeat file is `/tmp/cluetooth-sync/heartbeat.json` inside the
  container, rewritten every 15 s. It holds the phase, the current cycle's
  counts, total ok/failed/rows, consecutive error counts, the last errors, and
  the last projection time and watermark.
- The compose healthcheck runs `cluetooth health --max-age-seconds 120`. It is
  unhealthy if the heartbeat is stale or there are 5 or more consecutive cycle
  or projection errors.
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

## Deploy (intended, on hold)

1. Take a backup (above) and check `df -h /data /var/lib/docker`.
2. `git fetch` from the workstation remote, or apply the branch bundle, then
   `git checkout ops/continuous-sync` in the simi checkout.
3. Lower worker counts in `.env` (see above). Keep a copy:
   `cp .env .env.bak-YYYYMMDD`.
4. `docker compose build sync-continuous`.
5. `docker compose up -d sync-continuous`. Compose first runs `migrate`
   (dbmate `up`), which applies:
   - `20260805233000_coordinate_contract_checks`: `NOT VALID` checks. These
     take a brief `ACCESS EXCLUSIVE` lock on `scans`/`gatt_discoveries`, with
     no table scan.
   - `20261025093000_blob_retry_backoff`: adds 2 columns to `blobs`
     (metadata-only).
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
3. Both new migrations are additive and can stay. To remove them:

   ```sh
   docker compose run --rm migrate --url ... --migrations-dir /db/migrations down
   ```

   Run it once per migration, newest first.
4. Restore the dump only if data was damaged. Confirm with the owner first.

## Pending inventory (survey, 2026-10-25)

| kind | objects | succeeded | failed | new |
|---|---|---|---|---|
| legacy flat | 10050 | 10020 | 1 (invalid hex `g` in raw) | 29 (Aug 17, Sep 5, Oct 7) |
| `scans/v2/` | 22 | 0 | 0 | 22 (all Aug 2026) |
| `scans/schema=v2/` | 6 | 0 | 0 | 6 (Aug 2026, will be rejected) |

DB at survey time: 6.5 GB, `scans` 12.26M rows, last scan 2026-08-01.
