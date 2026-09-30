# Cluetooth Android

This directory prepares Android `0.0.5` (`versionCode = 8`, `minSdk = 24`). Debug builds retain the `.debug` application ID suffix for side-by-side installation. Android `0.0.5` is payload-v2-only: observations are no longer fed into the legacy JSONL writer. Phase 4 asks Rust to prepare sealed-box ciphertext and uses WorkManager/Firebase to upload the stable returned object path.

## Phase 4 durable core and uploader

`CluetoothApplication` owns one `CluetoothRepository` and that repository owns the only UniFFI core opened for the Android process. Repository initialization opens and refreshes it on a background dispatcher. The repository serializes calls through a bounded 1,024-command ingress, sends BLE input in batches of up to 64 observations or 250 ms, requests a durable WAL checkpoint every 30 seconds, and publishes scan-derived `AppUiState` no more often than every 500 ms. Checkpointing does not rotate below the core's 10 MiB/five-minute/100,000-row emergency limits. At 128 retained observations it accepts the threshold observation and pauses BLE scanning; it resumes only after durable recovery at or below 32. Sustained failure is visible as paused/degraded state, does not grow memory without bound, and normal close cannot retry forever. The separate UI-only scan flow is bounded and drops oldest display updates without affecting authoritative v2 admission. Core state includes active rows/estimated bytes and valid/invalid pending counts. Explicit user Stop and ViewModel teardown use the same main-handler callback fence before sealing/completing the Rust-owned session; failed or timed-out completion remains visible as degraded UI state. Activity `ON_STOP` deliberately does not stop scanning: Home, app switching, screen off, Bluetooth recovery, and backpressure preserve scan intent and session identity while the process/device policy permits. Repository/ViewModel teardown makes a bounded best-effort finalization where Android lifecycle permits. An OS kill can still interrupt before these callbacks complete.

`BleScanService` forwards observations with wall-clock time derived from `ScanResult.timestampNanos` and a paired system-clock snapshot, exact raw bytes, and nullable `ScanRecord.deviceName`. Its status flow exposes active, backpressure-paused, Bluetooth-off, failed, and stopped states; lifecycle stop always drains a main-handler callback fence before the final flush. Its `BleRecord` flow remains for the current UI, but `ScanViewModel` no longer calls legacy `StorageService.addRecord`, preventing unsupported `0.0.5` JSONL output. `UploadWorker` obtains v2 APIs from the application-owned repository and never opens the core directory itself. A process-wide coordinator mutex serializes one-time and periodic v2 transfer/ack sessions, and WorkManager reconciliation uses KEEP so a later request cannot replace an active transfer. The worker uploads prepared ciphertext with Firebase `putFile`, acknowledges results to Rust, and rethrows cancellation without recording upload failure. It also retains flat pre-`0.0.5` legacy upload; unsupported `0.0.5` legacy files are quarantined. Startup and seal effects schedule WorkManager reconciliation.

Pending legacy filenames retain their original producer version and remain upload-compatible; Android and sync recognize only `0.0.1`–`0.0.4` (including suffixes) as legacy. `LocationService` forwards accepted fixes with `Location.elapsedRealtimeNanos`; stop/unavailable/failure paths clear Rust location state. Rust uses a default five-second freshness window and never persists fixes across reopen. See `../docs/payload-schema-v2.md` for pending layout and the exact unsealed-observation crash window.

## Scan sessions, exports, and maps

The upload screen is session-oriented: it shows chronological active/completed/interrupted sessions, upload state, route/statistics/structural clusters, session export/deletion, and full local export. Internal payload filenames, chunk counts, schema labels, upload markers, and raw cleanup controls are not shown. Retained pre-0.0.5 files appear as synthetic legacy sessions.

Rust prepares one combined JSONL or Parquet artifact for a v2 session and a ZIP with one combined artifact per retained v2 session plus a checksummed manifest for full export. Android first requires an acknowledged flush, then only copies the prepared app-private file to the user-selected SAF URI and acknowledges cleanup. For retained legacy scan v1, Kotlin decodes the historical app-private JSONL into domain rows and Rust/Polars prepares the combined JSONL or Parquet artifact; full archives include these synthetic sessions rather than silently omitting them. Export Parquet is marked as a session export and is never presented as a sync-ingest payload.

Maps Compose renders only Rust-derived local route and observation overlays. No Routes, reverse-geocoding, or Static Maps route API is used. Google still receives normal viewport/tile requests. Supply `CLUETOOTH_MAPS_API_KEY` through a Gradle property or environment variable; never check it in. Restrict it to the Android package/signing certificate and enable only Maps SDK for Android. With no key, the detail screen safely shows a map-unavailable message. A future renderer seam is retained if viewport disclosure later requires MapLibre/offline tiles.

## Local setup

1. Install JDK 11 or newer, Android SDK platform 35, and the pinned Android NDK `27.2.12479018`.
2. Create ignored `local.properties` with your local SDK path (Android Studio can do this).
3. Supply an ignored `app/google-services.json` for builds that evaluate the Firebase plugin.
4. Run the JVM baseline tests:

   ```sh
   ./gradlew :app:testDebugUnitTest
   ```

Local properties, Firebase configuration, signing keys, APKs/AABs, IDE state, and generated build directories must remain untracked.

## Release signing

Release signing is configured only when all four values below are available. Each may be a Gradle property (`-P...` or user-level `~/.gradle/gradle.properties`) or an environment variable:

- `CLUETOOTH_RELEASE_STORE_FILE`
- `CLUETOOTH_RELEASE_STORE_PASSWORD`
- `CLUETOOTH_RELEASE_KEY_ALIAS`
- `CLUETOOTH_RELEASE_KEY_PASSWORD`

Never add these values or the keystore to this repository. Without them, debug builds/tests still work and release output is unsigned.

## Rust core build tasks

NDK `27.2.12479018` is pinned in the Android DSL, and the native script requires `cargo-ndk 4.1.2`. See `../cluetooth-core/README.md` for exact installation commands and direct-script usage.

Deterministic Gradle entry points are:

```sh
# Individual build outputs
./gradlew :app:generateCluetoothCoreBindings
./gradlew :app:buildCluetoothCoreAndroid

# Both generated outputs
./gradlew :app:prepareCluetoothCoreAndroid

# Phase 4 host/JVM/build validation (does not install a test APK)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The binding task writes Kotlin to `app/build/generated/source/uniffi`. The native task writes stripped API-24 `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` libraries to `app/build/generated/jniLibs`. Kotlin compilation depends on binding generation, and JNI merge/package tasks depend on the native build, so clean debug and instrumentation builds cannot silently omit generated inputs.

The app produces one APK per supported ABI and no universal APK. Its ABI split allowlist contains only the four values above, which excludes legacy `armeabi`, MIPS, and MIPS64 libraries from transitive AARs. Every resulting app APK contains exactly one matching `libcluetooth_core.so`.

The existing network-free instrumentation smoke-test source remains in the project, but Phase 2 validation intentionally does not invoke `connectedAndroidTest` or install a test APK. Generated Kotlin, native libraries, APKs, and test results remain ignored build state and must not be committed. The parent may manually reinstall and launch only the arm64 debug app after host/build validation.
