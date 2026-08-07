# Cluetooth payload schema v2

Status: authoritative producer/consumer contract for payload schema `v2`.

The Android source was imported from the `0.0.4` baseline, and this feature branch prepares Android `0.0.5`. Android `0.0.5` is reserved for payload schema v2 and must never be interpreted as a legacy producer version, including build/debug suffixes. Phase 4 keeps plaintext v2 Parquet pending until Firebase confirms upload, but Rust now owns sealed-box encryption, retry identity, ciphertext cache publication, and success/failure acknowledgement. Android owns only WorkManager and Firebase file transfer.

## Identity and object path

Each sealed payload rotation has one UUIDv7 identity. Generate it once, persist it with the pending payload, and reuse it for every retry. Re-encryption may change ciphertext, but it must not change the payload ID or object path.

The canonical object path is:

```text
scans/v2/YYYY/MM/DD/<uuidv7>.parquet.encrypted
```

`<uuidv7>` is the canonical lowercase hyphenated UUID. `YYYY/MM/DD` is UTC and is derived from the timestamp encoded by that UUIDv7. The same identity is stored in the local filename and Parquet footer.

## Ordered Parquet schema

Every Parquet file must contain all ten columns below, exactly once and in this order. Column presence and value nullability are separate parts of the contract: `addr`, `scanned_at`, and `raw` are logically required and must contain zero null values; the other seven columns may contain null values.

| Position | Column | Wire type | Required/nullability |
| ---: | --- | --- | --- |
| 1 | `addr` | UTF-8 string | required |
| 2 | `rssi` | Int32 | nullable |
| 3 | `scanned_at` | timestamp, UTC, milliseconds | required |
| 4 | `raw` | Binary | required |
| 5 | `local_name` | UTF-8 string | nullable |
| 6 | `tx_power` | Int32 | nullable |
| 7 | `is_connectable` | Boolean | nullable |
| 8 | `lat` | Float64 | nullable |
| 9 | `lon` | Float64 | nullable |
| 10 | `accuracy` | Float32 | nullable |

`raw` contains the exact authoritative BLE advertisement bytes. `addr` must be canonical uppercase colon-separated MAC text. `rssi` and `tx_power` remain Parquet Int32 but must fit PostgreSQL signed-smallint range. Latitude/longitude must be paired, finite, and within `[-90, 90]` / `[-180, 180]`; accuracy is optional only when coordinates exist and, when present, must be finite and nonnegative. These rules are validated by both producer and v2 sync consumer before publication/insertion. PostgreSQL adds `NOT VALID` coordinate pairing/range and accuracy checks as defense in depth for new writes without blocking deployment on pre-contract historical rows; explicit upper bounds reject PostgreSQL NaN/Infinity ordering behavior rather than relying on ordinary nonnegative comparisons. Producers must not encode it as hexadecimal/base64, deduplicate it, or apply protocol-specific transformations. Int32 is the persisted wire type for both integer columns even when an input API uses a narrower integer type.

Polars emits nullable Arrow fields when writing a `DataFrame`, so the physical Parquet leaves may use `OPTIONAL` repetition even for the three logically required columns. Physical `OPTIONAL` repetition does not weaken the value-level contract: consumers must reject a file if `addr`, `scanned_at`, or `raw` contains any null value. Producers and consumers must also preserve and validate the exact ordered dtypes shown above.

## Required footer metadata

Every file contains these Parquet file-level key/value pairs:

```text
cluetooth.payload_schema = v2
cluetooth.payload_id = <uuidv7>
```

The metadata payload ID must exactly match the canonical UUID in the object path. Mobile producers may also write `cluetooth.scan_session_id = <uuidv7>` for local session grouping. This optional footer value does not alter the ordered row schema, object identity, ingestion, or database model; sync ignores it. Optional app/producer metadata is diagnostic only and must never select the decoder.

## Compression and encryption

Parquet pages use internal Zstd compression. There is no outer `.zst` layer. The complete Parquet file is encrypted for upload, producing the `.parquet.encrypted` object.

## Local batching and publication policy

The production core rotates an active batch when a completed admitted batch reaches a configured limit: 10 MiB estimated uncompressed row bytes, five minutes from the first row, or the 100,000-row emergency ceiling. Configuration cannot exceed 1,000,000 rows, 256 MiB, or 24 hours. Android requests a durable active-payload checkpoint every 30 seconds; a checkpoint appends newly accepted rows to the WAL and does **not** rotate below those limits. `flush_payload` seals a non-empty partial batch at explicit Stop/session finish, export preparation, repository close, and other declared finalization boundaries. A zero-row checkpoint or flush publishes no file. The sync consumer nevertheless accepts a valid empty exact-schema v2 payload and marks its blob successful so it cannot be rediscovered forever.

The UniFFI call accepts at most 64 observations and 1 MiB of estimated input bytes. Oversized calls fail before mutation, so callers may retry without duplicating a partially accepted batch. Rotation is batch-granular: a file can overshoot a configured threshold by at most one bounded converted input batch (63 rows or at most 1,049,856 estimated bytes beyond the prior active state).

Pending plaintext files live under the core data directory at:

```text
pending/scans/v2/YYYY/MM/DD/<uuidv7>.parquet
```

For canonical publication, the production store opens the core root without following a symlink, then creates `pending`, `scans`, `v2`, `YYYY`, `MM`, and `DD` one level at a time with directory-relative operations. Every component is reopened with directory/no-follow flags; a symlink or non-directory component is rejected. For each newly created level it fsyncs the new directory and then its parent before continuing. In the held leaf-directory handle it creates a unique regular `.cluetooth-payload-*.tmp` with create-exclusive/no-follow flags, writes Parquet, fsyncs the file, inspects it, and atomically renames it with no-replace semantics to the generated UUID filename. Before removing the WAL/manifest, both a new rename and an exact-existing recovery run the same durability proof: reopen the complete hierarchy directory-relative without following links, verify the final regular file's device/inode, fsync and revalidate that file, then fsync and revalidate `DD`, `MM`, `YYYY`, `v2`, `scans`, `pending`, and the core root in leaf-to-root order. A failure at any file, directory, or revalidation step retains staging, and retry repeats the complete proof even when every path component already exists. WAL removal is synced in the staging directory; manifest removal is synced there; removal of the empty staging directory is synced in the core root.

A pre-existing final symlink, directory, device, or non-matching regular file is rejected and staging is retained. A concurrent attacker cannot redirect the directory-relative write outside the held core hierarchy. There remains an unavoidable unprivileged-filesystem TOCTOU interval between final hierarchy/inode revalidation and staging cleanup: a process with permission to rename app-private directories could make the durable published inode unreachable in that interval. Android app-private storage normally excludes such a peer; the core does not claim safety against a same-UID malicious process. Failed writes otherwise keep active rows and their once-assigned UUID for retry. If publication completed but cleanup did not, restart accepts the existing file only after footer, schema, session, row count, and every value exactly match the retained batch.

On reopen, the core recursively removes only regular files matching its owned payload/upload/state temporary naming policies. It discovers exact pending Parquet paths, then validates UUID version/canonical form/date, footer identity, schema, and required values. Corrupt or mismatched `.parquet` files remain in place, are excluded from upload candidates, and increment `invalid_pending_payload_count`; unrelated files and symlinks are ignored.

The owned publication sequence individually syncs every newly created ancestor, then proves the completed file and complete leaf-to-root ancestry durable after atomic rename or exact-existing recovery. On Android/Linux filesystems that honor `fsync`, this covers process death and sudden power loss up to the filesystem/device's own durability guarantees.

## Encryption and upload state

`prepare_upload(payload_id)` validates a current pending identity and atomically regenerates a randomized libsodium/PyNaCl-compatible `crypto_box_seal` ciphertext below `upload-cache/` from pending plaintext and the current validated recipient key. It never trusts or reuses cached ciphertext by length. Its returned cloud object path is always the identity-derived canonical path above.

Firebase may accept an upload before Android records the local result. A retry therefore regenerates ciphertext and uploads (or overwrites) the same object URI. Only `mark_upload_succeeded(payload_id)` writes a durable success marker and atomically moves validated plaintext into the app-private session archive while removing ciphertext and failure diagnostics. The marker is written before the archive move, so reopen completes the move after a crash; repeated success acknowledgement is idempotent. `mark_upload_failed` removes transient ciphertext, retains plaintext and retry identity, and records at most 512 UTF-8 bytes at a valid boundary. Unknown IDs are rejected. Reopen excludes/quarantines invalid plaintext, removes orphan ciphertext, reconciles success markers, and exposes pending/prepared/failed counts plus the last bounded diagnostic. Success tombstones remain indefinitely to preserve idempotent ack/reopen cleanup, so their storage and startup scan cost must be monitored.

## Mobile scan sessions and local exports

Android creates a new session UUIDv7 only on a genuine user stopped-to-started transition. Payload rotations, Activity backgrounding, Bluetooth recovery, and storage backpressure retain the current session. Explicit Stop drains callbacks, seals the final non-empty payload, and completes the session. An open session found after process death is marked interrupted.

Uploaded plaintext remains app-private under a session archive until explicit local session deletion; cloud identity and contents are unchanged. Session JSONL export combines retained chunks in deterministic chronological order and represents `raw` as lowercase hexadecimal while retaining every nullable schema field. Session Parquet export is one combined schema-correct file marked with `cluetooth.export = scan_session`; it deliberately has no payload ID and is not a sync-ingest object. Full local export is a ZIP with one combined artifact per retained session and a manifest containing SHA-256 checksums. Export temporaries are app-private and removed after Android copies them to a Storage Access Framework destination or on next core startup.

### Durable checkpoint, recovery, and exact loss window

The active payload uses an internal unreleased staging format (`active-payload/manifest.bin` plus `rows.wal`). Format v2 WAL rows have a checksummed header covering magic, format version, payload length, frame row count, and payload checksum; the trailer duplicates the length and checksums the header, payload, and duplicate length. WAL bytes are synced before an atomic, file-synced manifest replacement and staging-directory sync. Recovery allocates the fixed 77-byte manifest only after checking its exact metadata length. It truncates only an actually incomplete final header, payload, or trailer. Any complete magic/version/length/row-count/payload/header/trailer corruption rejects recovery and preserves the evidence. An empty WAL or incomplete first frame paired with a zero-row manifest is durably removed and cannot lend identity/session/start time to later observations. WAL-ahead rows are recovered in exact order and repair the manifest; a manifest claiming absent rows is rejected.

After each successful 30-second checkpoint, rows represented by the synced WAL are recoverable and reopen finalizes them under their original UUIDv7/session. The normal loss window is therefore observations accepted since the last successful checkpoint plus observations still queued in Kotlin. Explicit Stop/session finish, export preparation, and repository close request a flush after a structured BLE callback fence where applicable. Process death can still lose rows in that window. A complete temporary Parquet file is not itself recovery state and is removed on reopen. Process death after the atomic final rename is recoverable through either pending discovery or publication-before-staging-cleanup reconciliation.

## Compatibility and rollout

Legacy scan v1 is the existing flat JSONL/Zstd/encrypted contract from Android `0.0.1` through `0.0.4`, including version suffixes; it remains supported and is distinct from Parquet scan v2 at `scans/v2/...`. A nested `scans/v1/...` object is not legacy and is rejected. The future GATT contract is independently rooted at `gatt/v1/YYYY/MM/DD/<uuidv7>.json.encrypted`. Pending pre-`0.0.5` Android files remain compatible because `UploadWorker` preserves the original local producer filename and version when it derives the remote object name. A temporary legacy `0.0.5-debug` object is unsupported and may be deleted.

Prerelease debug v2 objects under the former `scans/schema=v2/...` path or written with the former Float64 `accuracy` draft are incompatible test artifacts and must be removed before rollout; this work does not access or delete cloud data. Rollout is sync-first: deploy and validate the v2 sync consumer before releasing Android `0.0.5`. Android uses one application/repository-owned Rust core for the process, and a process-wide coordinator serializes one-time and periodic v2 transfers and acknowledgements. Android forwards observations to the Rust batching core, creates no new legacy `0.0.5` JSONL, asks Rust to prepare ciphertext, uploads the exact returned path with Firebase `putFile`, and reports success/failure to Rust. WorkManager reconciles both v2 and supported legacy scan v1 local files on startup and network retry. Unsupported legacy `0.0.5` local files are quarantined rather than uploaded. A build-time invariant and JVM test prevent a `0.0.5` build with the v2 uploader flag disabled. Keep sync v2 deployed first, then canary one producer and monitor blob failures before broad rollout.
