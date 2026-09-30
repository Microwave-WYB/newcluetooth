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

## Local setup (from the repository root)

AGP 8.10 requires JDK **17**, Gradle 8.11.1 (the retained wrapper), SDK platform 35 and build-tools 35.0.0. Native packaging additionally requires NDK 27.2.12479018, cargo-ndk 4.1.2 and all four Android Rust targets.

1. Run `mise install` for isolated host Rust/Java and Android command-line tools, then `mise run install` for locked Python dependencies. Cargo/rustup and SDK packages live under `~/.local/share/mise/newcluetooth/`, not global Rust homes or a shared Android Studio SDK. No tool task authenticates or accepts SDK licenses.
2. **Manually** review and accept Android licenses in the project SDK home, if you agree. This is a human action, not part of install/check:

   ```sh
   mise exec -- sh -c 'mkdir -p "$ANDROID_HOME"; sdkmanager --sdk_root="$ANDROID_HOME" --licenses'
   ```

3. Install only the host SDK packages first. Native prerequisites are a separate opt-in:

   ```sh
   mise run //cluetooth-android:sdk-install        # platform35/build-tools35.0.0 only; no license acceptance
   # Only for a later explicitly requested native ABI build:
   mise run //cluetooth-android:native-sdk-install # NDK27.2.12479018
   mise run //cluetooth-core:native-install       # all four Rust targets + locked cargo-ndk4.1.2
   ```

   Host JVM compilation needs SDK platform/build-tools but not the NDK/ABI build. Missing licenses/packages are real blockers; do not skip tests or point at production resources to work around them. `local.properties` is optional when mise supplies `ANDROID_HOME`; keep any local override untracked.
4. Run `mise run //cluetooth-android:check`. Host tasks compile real generated UniFFI/Kotlin, execute JVM tests and run lint with `abortOnError = true`. They explicitly exclude only `:app:processDebugGoogleServices` and `:app:buildCluetoothCoreAndroid`: no Firebase processing, ABI packaging, APK, device install or app startup. Excluding those packaging/config tasks does **not** exclude Kotlin compilation, tests or lint. Host success is not native/APK/device validation.

### Firebase client configuration and environments

The user-provided tracked `app/google-services.json` is client-only public APK metadata for project `cluetooth-1da02`, bucket `cluetooth-1da02.firebasestorage.app`, package `edu.ucsd.sysnet.cluetoothscanner`. It is production-associated, **not a test environment**, and does not grant access: Firebase rules, App Check and API-key restrictions are separate.

The retained debug application ID is `edu.ucsd.sysnet.cluetoothscanner.debug`. The supplied JSON contains no matching debug client, so product `assembleDebug` must fail Google Services processing until a corresponding client is explicitly registered/supplied, preferably in a separately chosen test project/bucket. Do not remove the suffix, edit a client package or silently introduce a fallback. Test-environment provisioning is a separate decision. Base/release config presence only removes the release Firebase-client blocker; it proves neither native packaging nor runtime/security behavior.

User-supplied client JSON may be committed after verifying its type, package and intended environment; narrowly adjust only the intended config path. Never commit service-account credentials, private encryption keys, signing keys/keystores, local properties, generated builds or IDE state.

## Release signing

Release signing is configured only when all four values below are available. Each may be a Gradle property (`-P...` or user-level `~/.gradle/gradle.properties`) or an environment variable:

- `CLUETOOTH_RELEASE_STORE_FILE`
- `CLUETOOTH_RELEASE_STORE_PASSWORD`
- `CLUETOOTH_RELEASE_KEY_ALIAS`
- `CLUETOOTH_RELEASE_KEY_PASSWORD`

Never add these values or the keystore to this repository. Host JVM tests need no signing material. Signing and Firebase package matching are separate prerequisites; release output is unsigned when no signing values are supplied.

## Build and runtime boundaries

```sh
mise run //cluetooth-core:bindings        # generated host UniFFI Kotlin
mise run native-check                    # four API-24 native ABIs, no APK
mise run //cluetooth-android:build        # product debug APKs, matching .debug client required
mise run //cluetooth-android:build-release # product release APKs, unsigned without signing values
```

The Gradle equivalents remain `:app:generateCluetoothCoreBindings`, `:app:buildCluetoothCoreAndroid`, `:app:prepareCluetoothCoreAndroid`, `:app:assembleDebug`, and `:app:assembleRelease`. Kotlin compilation depends on binding generation; JNI merge/package tasks depend on the native build. Generated inputs cannot silently be omitted from product builds. Bindings go to `app/build/generated/source/uniffi`; stripped native libraries go to `app/build/generated/jniLibs`. All native targets/tool versions are checked before building; no ABI is silently skipped.

The app produces one APK per supported ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`), no universal APK. Host JVM tests do not assert packaged libraries, installation, API-24 loading or on-device behavior.

**Device smoke is opt-in only:** `mise run //cluetooth-android:device-smoke` refuses unless `CLUETOOTH_ALLOW_DEVICE_SMOKE=1` is set. It installs and launches test/app components. Although the smoke-test body uses synthetic local data, `CluetoothApplication.onCreate` schedules uploads, so the complete instrumentation run is **not guaranteed network-free**. A separately reviewed non-production/offline app and device/network setup is required before opting in; the existing production client config does not satisfy that requirement. Never use this task in default checks, and do not infer it is safe merely from its test source. This integration does not perform device operations.
