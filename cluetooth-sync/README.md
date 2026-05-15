# cluetooth-sync

Sync encrypted BLE scan blobs into Postgres/PostGIS, then export eligible scan
rows to WiGLE-compatible Bluetooth CSV batches.

## WiGLE Uploads With Docker Compose

WiGLE uploads read already-ingested rows from Postgres. Run a sync first, or run
the upload against an existing database volume that already contains scans.

1. Copy the root environment template and fill in credentials:

   ```sh
   cp .env.example .env
   ```

   Set `WIGLE_API_KEY` to `base64(api-name:api-token)`.

2. Start the database and migrations:

   ```sh
   docker compose up -d postgres migrate
   ```

3. Ingest scans if the database is not already populated:

   ```sh
   docker compose --profile manual run --rm sync
   ```

4. Upload all pending WiGLE batches:

   ```sh
   docker compose --profile manual run --rm wigle-upload
   ```

   The service creates or resumes tracked batches until no eligible rows remain.
   Each batch is written as a temporary CSV inside the one-shot container,
   uploaded to WiGLE, and recorded with its WiGLE transaction id in
   `wigle_upload_batches`.

Useful overrides:

```sh
# Limit the number of scan rows claimed by each temporary CSV batch.
CLUETOOTH_WIGLE_BATCH_SIZE=1000 docker compose --profile manual run --rm wigle-upload

# Upload a specific existing batch.
docker compose --profile manual run --rm wigle-upload wigle upload --batch-id 12

# Retry an explicitly selected failed batch.
docker compose --profile manual run --rm wigle-upload wigle upload --batch-id 12 --retry-failed

# Check WiGLE processing status for uploaded batches.
docker compose --profile manual run --rm wigle-upload wigle status

# List tracked upload batches.
docker compose --profile manual run --rm wigle-upload wigle batches
```

By default uploads use WiGLE's non-donation mode. Pass `--donate` to
`wigle upload` only when the file contents should be marked for commercial-use
donation.
