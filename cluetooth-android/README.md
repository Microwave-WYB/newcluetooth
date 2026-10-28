# Cluetooth Android

This directory prepares Android `0.0.5` (`versionCode = 11`, `minSdk = 24`). Debug builds retain the `.debug` application ID suffix for side-by-side installation. Android `0.0.5` is payload-v2-only: observations are no longer fed into the legacy JSONL writer. Phase 4 asks Rust to prepare sealed-box ciphertext and uses WorkManager/Firebase to upload the stable returned object path.

## Phase 4 durable core and uploader

`CluetoothApplication` owns one `CluetoothRepository` and that repository owns the only UniFFI core opened for the Android process. Repository initialization opens and refreshes it on a background dispatcher. The repository serializes calls through a bounded 1,024-command ingress, sends BLE input in batches of up to 64 observations or 250 ms, requests a durable WAL checkpoint every 30 seconds, and publishes scan-derived `AppUiState` no more often than every 500 ms. Checkpointing does not rotate below the core's 10 MiB/five-minute/100,000-row emergency limits. At 128 retained observations it accepts the threshold observation and pauses BLE scanning; it resumes only after durable recovery at or below 32. Sustained failure is visible as paused/degraded state, does not grow memory without bound, and normal close cannot retry forever. The separate UI-only scan flow is bounded and drops oldest display updates without affecting authoritative v2 admission. Core state includes active rows/estimated bytes and valid/invalid pending counts. Explicit user Stop and ViewModel teardown use the same main-handler callback fence before sealing/completing the Rust-owned session; failed or timed-out completion remains visible as degraded UI state. Activity `ON_STOP` deliberately does not stop scanning: Home, app switching, screen off, Bluetooth recovery, and backpressure preserve scan intent and session identity while the process/device policy permits. Repository/ViewModel teardown makes a bounded best-effort finalization where Android lifecycle permits. An OS kill can still interrupt before these callbacks complete.

`BleScanService` forwards observations with wall-clock time derived from `ScanResult.timestampNanos` and a paired system-clock snapshot, exact raw bytes, and nullable `ScanRecord.deviceName`. Its status flow exposes active, backpressure-paused, Bluetooth-off, failed, and stopped states; lifecycle stop always drains a main-handler callback fence before the final flush. Its `BleRecord` flow remains for the current UI, but `ScanViewModel` no longer calls legacy `StorageService.addRecord`, preventing unsupported `0.0.5` JSONL output. `UploadWorker` obtains v2 APIs from the application-owned repository and never opens the core directory itself. A process-wide coordinator mutex serializes one-time and periodic v2 transfer/ack sessions, and WorkManager reconciliation uses KEEP so a later request cannot replace an active transfer. The worker uploads prepared ciphertext with Firebase `putFile`, acknowledges results to Rust, and rethrows cancellation without recording upload failure. It also retains flat pre-`0.0.5` legacy upload; unsupported `0.0.5` legacy files are quarantined. Startup and seal effects schedule WorkManager reconciliation.

Pending legacy filenames retain their original producer version and remain upload-compatible; Android and sync recognize only `0.0.1`–`0.0.4` (including suffixes) as legacy. `LocationService` forwards accepted fixes with `Location.elapsedRealtimeNanos`; stop/unavailable/failure paths clear Rust location state. Rust uses a default five-second freshness window and never persists fixes across reopen. See `../docs/payload-schema-v2.md` for pending layout and the exact unsealed-observation crash window.

## Scan sessions, exports, and maps

The upload screen is session-oriented: it shows chronological active/completed/interrupted sessions, upload state, route/statistics/structural clusters, session export/deletion, and full local export. Internal payload filenames, chunk counts, schema labels, upload markers, and raw cleanup controls are not shown. Retained pre-0.0.5 files appear as synthetic legacy sessions.

Rust prepares one combined JSONL or Parquet artifact for a v2 session and a ZIP with one combined artifact per retained v2 session plus a checksummed manifest for full export. Android first requires an acknowledged flush, then only copies the prepared app-private file to the user-selected SAF URI and acknowledges cleanup. For retained legacy scan v1, Kotlin decodes the historical app-private JSONL into domain rows and Rust/Polars prepares the combined JSONL or Parquet artifact; full archives include these synthetic sessions rather than silently omitting them. Export Parquet is marked as a session export and is never presented as a sync-ingest payload.

Maps Compose renders only Rust-derived local route and observation overlays. No Routes, reverse-geocoding, or Static Maps route API is used. Google still receives normal viewport/tile requests. Supply `CLUETOOTH_MAPS_API_KEY` through a Gradle property or environment variable; never check it in. Restrict it to the Android package/signing certificate and enable only Maps SDK for Android. With no key, the detail screen safely shows a map-unavailable message. A future renderer seam is retained if viewport disclosure later requires MapLibre/offline tiles.

## v3 UI previews

`app/src/main/.../ui/v3/` holds the v3 Compose screens (scan, device detail, nearby, history, maps) built on plain UI state classes in `ui/v3/model/` and fake data in `FakeData`; nothing there is wired to the repository, GATT engine or MapLibre yet. `app/src/screenshotTest/` renders every screen in light and dark at phone size with the Compose Preview Screenshot Testing plugin. Run `mise run //cluetooth-android:ui-shots /some/dir` to write the PNGs and an `index.html` gallery; layoutlib needs JDK 21, so the task selects `temurin-21` (`mise install java@temurin-21`). The reference PNGs under `app/src/screenshotTestDebug/` are build output and are git-ignored. These tasks are not part of `mise run check`.

### v3 UI wiring

`MainActivity` shows `V3App` (`ui/v3/app/`) once permissions are granted; the old scan screen is gone from the flow and the old sessions/upload screen stays behind the gear icon (`Route.Legacy`). `V3ViewModel` builds every screen's state from:

* `BleScanService.liveScans`, aggregated per MAC by `LiveDeviceTracker` (RSSI history, ad variants) and ordered by `ScanOrder` (one RSSI sort 3 s after the first device, then fixed; pull-to-refresh re-sorts; new devices are appended);
* `RecordingController` (the record button: round idle button, expanded bottom-right controls on the Scan page, floating top-right pill elsewhere; control bar; stop snackbar) and `CluetoothApplication.localData` (Nearby, History, route, sightings, wiki matches);
* `GattDiscoveryManager` through `GattPresenter` (progress steps, services, characteristics, errors in words).

Maps use MapLibre (`org.maplibre.gl:android-sdk`, 12.3.1; 13.x is built with Kotlin 2.2 and does not link against this project's 2.0.21) with OpenFreeMap `liberty` / `dark` styles and the OSM/OpenFreeMap attribution drawn by the page. The density layer is blurred circles: MapLibre's `HeatmapLayer` segfaulted intermittently on an Adreno 640. Debug builds accept `am start -n <pkg>.debug/edu.ucsd.sysnet.cluetoothscanner.MainActivity --es v3_demo_map route|selected|heat|product` to open the map page over fake data. They also accept `--ez fake_scan true`, which feeds about 300 synthetic advertisers (long names, RSSI -30 to -105, devices that leave and return) into the live list only (`DebugFakeScan`, debug source set); nothing is recorded from them.

## Scan payload v3 and manual GATT discovery

New payloads are `scans/v3` (docs/payload-schema-v3.md): extended advertising is scanned with `setLegacy(false)` and `setPhy(PHY_LE_ALL_SUPPORTED)`; the "Scan LE Coded PHY" switch on the Upload screen (default on) selects 1M only when off and is re-read at every scan restart (at least once a minute). Before each scan (re)start `BleScanService` declares the footer facts (device, Android, app version, capabilities, PHY in force) to the core, which seals the payload in progress whenever they change.

`gatt/` is the manual GATT engine. `GattEngine` is a stage machine (connect 10 s, MTU 517, discover 15 s, read every readable characteristic and every descriptor at 3 s each within 30 s, disconnect, always `close()`) over the `GattLink` interface, which has no write, notification or bonding call. Any disconnect, timeout or error keeps what was collected as a partial result; status 133 before any read is retried once after one second; `cancel()` works at any point. `GattDiscoveryManager` (one per process, in `CluetoothApplication`) exposes `start`, `cancel` and a `state` flow, builds the `gatt/v1` JSON (`GattJson`) and hands it to the Rust core, which validates, stores and uploads it through the shared WorkManager path and keeps the plaintext in `archive/gatt`. A one-slot preference (`cluetooth_gatt`) holds a `failed` object for the current stage while a discovery runs, so a discovery cut short by process death is stored as failed at the next start. The only trigger today is a debug-build panel in the device sheet (`GattDebugPanel`); release builds have no UI entry point.

Android notes: the stack may start pairing by itself when a read fails for authentication or encryption, and there is no public API to forbid that. The engine records the status and reads on; the operator cancels any pairing dialog. Android exposes instance ids, not ATT handles. `BLUETOOTH_CONNECT` (already part of the required runtime permissions) is needed to connect.

## Live scanning and recording

The scanner runs live whenever the app is in the foreground (`BleScanService.liveScans`, no core session, no rows); a recording (`RecordingController`: start, pause, resume, stop; `RecordingService` foreground service with a partial wake lock) opens the session and writes rows. Details: `docs/recording-model.md`.

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
4. Run `mise run //cluetooth-android:check`. Host tasks compile real generated UniFFI/Kotlin, execute JVM tests and run lint with `abortOnError = true`. They use `-PcluetoothHostChecks=true`, which leaves Google Services unapplied only for a nonempty request containing exactly `:app:testDebugUnitTest` and/or `:app:lintDebug`; other selectors or flag values fail before execution. Only `:app:buildCluetoothCoreAndroid` is excluded. Simply excluding the Google Services task breaks AGP's generated-resource provider. No Firebase resources, ABI packaging, APK, device install or app startup occur; compilation/tests/lint are not skipped. Product tasks apply Google Services normally. Host success is not native/APK/device validation.

### Firebase client configuration and environments

The user-provided tracked `app/google-services.json` is client-only public APK metadata for project `cluetooth-1da02`, bucket `cluetooth-1da02.firebasestorage.app`, package `edu.ucsd.sysnet.cluetoothscanner`. It is production-associated, **not a test environment**, and does not grant access: Firebase rules, App Check and API-key restrictions are separate.

The retained debug application ID is `edu.ucsd.sysnet.cluetoothscanner.debug`. The tracked `app/src/debug/google-services.json` is genuine registered client metadata with only `project_info.storage_bucket` overridden to the private test bucket `cluetooth-1da02-debug`. Google Services selects this build-type config for debug and the unchanged module-root config for release; it matches the appropriate package even though the debug config contains both registered clients. Both the v2 `FirebaseObjectFileUploader` and legacy `UploadWorker` use default `FirebaseStorage`, so both inherit the variant's generated `google_storage_bucket` without runtime routing code. Never remove the suffix, fabricate client identifiers or silently fall back to production.

User-supplied client JSON may be committed after verifying its type, package and intended environment; narrowly adjust only the intended config path. Never commit service-account credentials, private encryption keys, signing keys/keystores, local properties, generated builds or IDE state.

From `cluetooth-android/`, run `mise exec -- ./gradlew --offline --no-daemon --max-workers=2 :app:verifyFirebaseConfigs` to process both configs and assert the selected config, package, registered App ID, project/number and generated bucket resources against independent expected values. This task only depends on `processDebugGoogleServices` and `processReleaseGoogleServices`; it does not compile native/app code, package release, launch an app or call Firebase APIs. Debug must generate App ID `1:952828187654:android:24c3c17e0c06adbeb81f87` and bucket `cluetooth-1da02-debug`; release must retain App ID `1:952828187654:android:9a76ce60006c562cb81f87` and bucket `cluetooth-1da02.firebasestorage.app`.

The private bucket does **not** prove SDK uploads are authorized: Firebase bucket registration, Storage rules and upload authentication are deferred. Debug still uses Firebase project `cluetooth-1da02`, so this is bucket separation, **not full environment isolation**. Host checks leave Google Services unapplied and share `app/build` with product builds; they are not evidence of packaged Firebase configuration. Verify genuine APK resources separately before any explicitly authorized device/network operation.

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
mise run native-check                    # API-24 native ABIs, no APK (default arm64-v8a)
mise run //cluetooth-android:build        # product debug APKs, matching .debug client required
mise run //cluetooth-android:build-release # product release APKs, unsigned without signing values
```

The Gradle equivalents remain `:app:generateCluetoothCoreBindings`, `:app:buildCluetoothCoreAndroid`, `:app:prepareCluetoothCoreAndroid`, `:app:assembleDebug`, and `:app:assembleRelease`. Kotlin compilation depends on binding generation; JNI merge/package tasks depend on the native build. Generated inputs cannot silently be omitted from product builds. Bindings go to `app/build/generated/source/uniffi`; stripped native libraries go to `app/build/generated/jniLibs`. All native targets/tool versions are checked before building; no ABI is silently skipped.

Payload rotation (5 minutes, 10 MiB, 100k rows) is set in Kotlin in `core/PayloadRotationConfig.kt` and passed to the Rust core when it is opened; the core validates it against hard maximums and keeps its own constants only as defaults. Changing the interval is a Kotlin-only change and does not rebuild the native libraries (Gradle reuses them while the Rust inputs are unchanged).

Only `arm64-v8a` is built by default for both debug and release. Opt in to more ABIs (e.g. emulators) with `-PcluetoothAbis=arm64-v8a,x86_64` or `CLUETOOTH_ABIS=arm64-v8a,x86_64` (any of `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`); the same list drives the native build and the APK splits. Every requested ABI must be built and packaged; the build fails otherwise. One APK per requested ABI, no universal APK. Host JVM tests do not assert packaged libraries, installation, API-24 loading or on-device behavior.

**Device smoke is opt-in only:** `mise run //cluetooth-android:device-smoke` refuses unless `CLUETOOTH_ALLOW_DEVICE_SMOKE=1` is set. It installs and launches test/app components. Although the smoke-test body uses synthetic local data, `CluetoothApplication.onCreate` schedules uploads, so the complete instrumentation run is **not guaranteed network-free**. A separately reviewed non-production/offline app and device/network setup is required before opting in; the existing production client config does not satisfy that requirement. Never use this task in default checks, and do not infer it is safe merely from its test source. This integration does not perform device operations.
