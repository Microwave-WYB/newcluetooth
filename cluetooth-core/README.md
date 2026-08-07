# cluetooth-core

This pinned Rust library/`cdylib` contains the Polars + Parquet + stable UniFFI Kotlin stack and the durable payload/upload/session core. Its FFI is domain-oriented and exposes no Polars types. Android forwards canonical observations and locations here; Rust owns scan sessions, summaries, routes, structural clusters, local archives, combined exports, sealed-box encryption, and upload state while Kotlin owns Firebase and Storage Access Framework transfer.

## Core and payload semantics

`CluetoothCore.open(data_directory, config)` creates the data directory when needed and starts with empty ephemeral scan and location state. `state()` and `refresh()` return immutable lightweight snapshots, and state-changing domain operations return lightweight updates/results rather than datasets. Scan rotation writes pending v2 Parquet; upload preparation writes only Rust-owned ciphertext cache files.

`default_core_config()` uses a 5,000 ms maximum location age and a 32-fix ring. Five seconds is a conservative initial window for Android's current two-second location cadence: a fix exactly 5,000 ms old is accepted, while a fix even one nanosecond older is stale. Association uses Android monotonic timestamps and selects the newest fix at or before each scan, never a future fix. Fixes are bounded in memory, cleared explicitly when collection is unavailable/stopped, and are empty after reopen.

`record_observations` preserves each accepted raw observation without deduplication. Android supplies `ScanRecord.deviceName` as nullable `local_name`; Rust removes NUL characters and converts an empty result to null but does not parse advertisement bytes on the scan hot path. Raw bytes remain authoritative. Rich/raw BLE decoding is deferred to a later on-demand device-details phase.

The active payload rotates at 10 MiB estimated row bytes or five minutes by default, with a documented 100,000-row emergency ceiling that normally does not preempt those thresholds. Configuration is capped at 1,000,000 rows, 256 MiB estimated bytes, and 24 hours so callers cannot create effectively unbounded active retention. Each FFI input is prevalidated all-or-nothing at 64 rows and 1 MiB estimated bytes, matching the Android batch size. Rotation is batch-granular with at most one bounded input batch of threshold overshoot. `checkpoint_active_payload` durably appends unstaged rows without sealing before a threshold; `flush_payload` seals partial non-empty batches for lifecycle boundaries, while zero rows produce no file. The UUIDv7 assigned by the first checkpoint remains stable through Parquet publication and upload retry. Rotation atomically publishes Zstd Parquet under `pending/scans/v2/YYYY/MM/DD/<uuid>.parquet`; its stable future object path is `scans/v2/YYYY/MM/DD/<uuid>.parquet.encrypted`. `pending_uploads` and `CoreState.pending_upload_count` expose validated restart-discovered files. Temporary files are removed on reopen; invalid/corrupt Parquet files remain quarantined in place and count toward `invalid_pending_payload_count`.

`prepare_upload`, `mark_upload_succeeded`, and `mark_upload_failed` implement stable-path sealed-box upload state. Every preparation atomically regenerates ciphertext from validated plaintext and the current recipient key; cached ciphertext is never trusted by size or reused. Plaintext remains pending until explicit success; success moves it atomically into an app-private session archive, while failures retain retry identity with diagnostics bounded to 512 UTF-8 bytes. Reopen reconciles marker-before-move crashes and removes corrupt/orphan transient ciphertext. Encryption uses pinned pure-Rust `crypto_box` 0.9.1 with `seal`, compatible with libsodium/PyNaCl, and rejects non-contributory low-order recipient keys.

Archived plaintext remains available for session summaries, mapping, and combined JSONL/Parquet export until explicit session deletion. Pending/failed session deletion requires an explicit destructive flag; uploaded deletion affects only the local archive and leaves cloud data untouched. Export temporaries are removed after acknowledgement and wholesale on reopen. Success tombstones are intentionally retained indefinitely to preserve idempotent acknowledgement and reopen cleanup. Installations should monitor the `upload-state/succeeded` tree because startup reconciliation cost grows with its size; bounded deletion is deferred until an independently durable remote/ledger acknowledgement can preserve those semantics.

`start_scan_session` is idempotent while active and creates UUIDv7 identity only after the prior session has been completed. Route fixes are validated, consecutively deduplicated, retained independently from advertisements, bounded to 2,048 active points, and simplified to at most 512 points at completion. Reopen marks an active persisted session interrupted. Summaries derive observation, MAC, exact `(addr, raw)`, route/distance/accuracy, upload, and structural-cluster statistics from validated retained files. Cluster signatures preserve ordered AD types and declared length bytes, except types `0x08`/`0x09` use length `-1` solely to improve grouping.

Checkpointed observations live in the app-private `active-payload` directory as a fixed-size, checksummed binary manifest plus staging-format-v2 row frames; raw bytes are stored directly. Each frame checksums its magic/version/length/row-count/payload-checksum header, checksums its payload, and ends with a duplicated length plus whole-frame checksum. WAL data is synced before an atomic manifest replacement, so the manifest never truthfully claims unsynced rows. Recovery truncates only an incomplete final header/payload/trailer, rejects and retains corrupt complete frames or unsafe manifest/WAL mismatches, repairs a WAL-ahead manifest, and treats an empty/partial-first-frame zero-row stage as no payload. It finalizes recovered rows under their original session before a new session can start.

Canonical pending publication opens every generated component directory-relative with no-follow semantics and durably creates one level at a time. After a no-replace atomic rename or exact-existing recovery, it reopens the owned hierarchy, fsyncs and revalidates the Parquet inode, then fsyncs and revalidates every directory leaf-to-root through `pending` and its owning core root before sync-removing WAL/manifest/staging. Any proof failure retains the WAL, and retry repeats the complete proof even when all components already exist. Publication-before-cleanup recovery reuses the same identity without duplicate rows. A same-UID process able to rename app-private directories still has a narrow race between final revalidation and staging cleanup; directory-relative writes cannot be redirected outside the held hierarchy. Internal staging paths are never uploaded, exported, or exposed in session/UI models. See `../docs/payload-schema-v2.md` for the exact fsync sequence, flush boundaries, and loss window.

## Host validation

Rust `1.96.1` is pinned by `rust-toolchain.toml`; dependencies are pinned by exact versions and `Cargo.lock`.

```sh
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test --locked
cargo build --locked --release --lib
```

Tests cover deterministic UUIDv7/date/path validation, FFI row/byte prevalidation, batch-granular rotation, exact row values, atomic failure/retry, zero-row behavior, independent path-correct corruption/footer mismatch, restart discovery, temporary cleanup, sealed-box compatibility, crash/retry/ack reconciliation, orphan ciphertext regeneration, and bounded active retention. The schema integration test writes and reads real Zstd Parquet, verifies exact ordered types/footer metadata/raw bytes and logical nullability, rejects external files with null required values, and exercises atomic replacement/cleanup.

Regenerate the checked-in cross-language fixture reproducibly with:

```sh
cargo run --locked --example generate_payload_v2_fixture -- \
  ../cluetooth-sync/tests/fixtures/payload-v2
```

The expected file is `scans/v2/2025/03/24/0195c920-7c00-7abc-8def-0123456789ab.parquet` below that fixture root.

## Kotlin bindings

Generate reproducibly from the host release library on Linux (`.so`) or macOS (`.dylib`):

```sh
./scripts/generate-kotlin-bindings.sh \
  ../cluetooth-android/app/build/generated/source/uniffi
```

The equivalent Gradle task is `../cluetooth-android/gradlew -p ../cluetooth-android :app:generateCluetoothCoreBindings`. The package and native-library name are pinned in `uniffi.toml`. Generated Kotlin is build output and is not tracked. Binding generation requires Bash plus a native Rust host toolchain and fails with an explicit unsupported-host error outside Linux/macOS; native Windows binding generation is not implemented.

## Android API 24 native libraries

The Android native toolchain is pinned to NDK `27.2.12479018`, `cargo-ndk 4.1.2`, and the Rust `1.96.1` toolchain in `rust-toolchain.toml`. Install the exact prerequisites and all four Rust targets:

```sh
sdkmanager "ndk;27.2.12479018"
cargo install cargo-ndk --version 4.1.2 --locked
rustup target add --toolchain 1.96.1 \
  aarch64-linux-android armv7-linux-androideabi \
  x86_64-linux-android i686-linux-android
```

`build-android.sh` checks both tool versions and every Rust target before compiling. It honors `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`, then checks the pinned NDK under `ANDROID_SDK_ROOT`, `ANDROID_HOME`, or the standard `$HOME/Android/Sdk`. Version and missing-target failures include corrective commands.

Build all four verified ABIs at API 24:

```sh
./scripts/build-android.sh \
  ../cluetooth-android/app/build/generated/jniLibs
```

Or run `../cluetooth-android/gradlew -p ../cluetooth-android :app:buildCluetoothCoreAndroid`. The output contains `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`. Android uses the same four-value ABI split allowlist with no universal APK, so transitive `armeabi`, MIPS, or MIPS64 artifacts cannot create an APK that lacks `libcluetooth_core.so`.

`[profile.release] strip = "symbols"` reproducibly removes static symbols and debug sections while preserving the generated dynamic UniFFI API. Validate the current exports from each built ABI with the pinned NDK's `llvm-readelf --dyn-syms`; exact counts change when UniFFI surface metadata changes and are not a stable release invariant. Cargo's default unwind panic strategy is unchanged. Binding generation explicitly disables stripping only for its ignored host cdylib because UniFFI bindgen needs its static metadata; that host artifact is not packaged.

Verified NDK r27c/API-24 ELF results (bytes):

| Android ABI | ELF machine | Before strip | After strip | Needed libraries |
| --- | --- | ---: | ---: | --- |
| `arm64-v8a` | AArch64 / ELF64 | 42,498,480 | 28,354,784 | `libdl.so`, `libm.so`, `libc.so` |
| `armeabi-v7a` | ARM EABI5 / ELF32 | 26,393,044 | 17,516,004 | `libdl.so`, `libm.so`, `libc.so` |
| `x86_64` | AMD x86-64 / ELF64 | 42,457,280 | 31,813,616 | `libdl.so`, `libm.so`, `libc.so` |
| `x86` | Intel 80386 / ELF32 | 43,971,120 | 34,344,088 | `libdl.so`, `libm.so`, `libc.so` |

`file` and pinned-NDK `llvm-readelf` report all four outputs stripped and built for Android 24. The remaining Phase 1 native gate is executing the packaged instrumentation smoke test on a device/emulator; compilation alone is not an API-load/runtime claim.

## Measurements

Measured on the Phase 1 Linux x86-64 host with Rust 1.96.1:

- first optimized host `cdylib` build reported `2m 23s`;
- the surrounding timed release/generation command used a peak RSS of `3,119,568 KiB`.

The ignored host cdylib used by binding generation deliberately retains UniFFI static metadata, so its size is not representative of a packaged Android library. Use the verified per-ABI table above for package inputs.

A small reproducible host write-latency harness uses 10,000 rows, 31 raw advertisement bytes per row (310,000 raw bytes total), deterministic mixed nullable values, one warm-up, and five timed calls to the public atomic writer. Each timing includes Polars frame construction inside the writer, Zstd Parquet writing, file sync/close, inspection, and atomic replacement; input row construction is outside the timed region. Run it with:

```sh
cargo run --locked --release --example measure_write_latency
```

Actual output on the same Phase 1 Linux x86-64 host:

```text
host=linux-x86_64
dataset_rows=10000 raw_bytes_per_row=31 total_raw_bytes=310000
parquet_output_bytes=35789 compression=zstd
method=1_warmup_then_5_public_atomic_write_calls_including_frame_build_sync_and_inspection
sorted_run_1_milliseconds=4.007
sorted_run_2_milliseconds=4.153
sorted_run_3_milliseconds=4.492
sorted_run_4_milliseconds=4.628
sorted_run_5_milliseconds=4.652
median_write_milliseconds=4.492
performance_assertions=none
```

These are host feasibility measurements, not Android or APK measurements, and the harness intentionally has no flaky performance threshold. Android API-24 load, on-device peak memory, and on-device latency remain unverified until the connected instrumentation smoke test runs. The measurements above establish build, ELF, stripping, dependency, and packaging state only.
