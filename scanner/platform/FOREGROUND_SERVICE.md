# `:platform` foreground recording: design notes

These notes implement `docs/v2-api.md` §1.2 (processes and recording survival), §4.1 (scan → ingest), §4.2
(location), §4.4 (`Recorder`) and SPEC V-1, V-6, R-2, R-6 and R-7. They record what the v3 code taught and where v2
differs. The contract wins where the two disagree.

## 1. Ownership

| Piece | Lifetime | Owner |
|---|---|---|
| `Deferred<CoreIngest>` | process | `:app` `AppGraph` (opens `Core` on `Dispatchers.IO`) |
| `PlatformGraph`: `Recorder`, `BleScanner`, `ScanIngest`, `LocationClient`, `LocationStatusSource`, `SettingsStore`, `Uploads`, `GattRunner`, `Permissions` | process, built lazily | `:platform`, handed to `AppGraph` |
| `RecordingService` | while a recording is Starting, Recording, Paused or Stopping | `:platform`; observes `Recorder.state` |
| Activity, ViewModels, composition | disposable | UI lanes; nothing in `:platform` refers to them |

- `Recorder` is the only thing that starts or stops the scanner and the RECORDING location request. The service never
  starts or stops either one; it holds the foreground priority, the wake lock and the notification.
- The service holds **no** recording logic. That keeps the "UI is disposable" rule (§1.2.4) true for the service as
  well: if Android recreates the service object, nothing is lost.
- v3 had the same split (`RecordingController` + `RecordingService`), with live scanning on top. v2 drops live
  scanning (V-1), so v3's `ScanSupervisor` foreground/background reconciliation collapses into the `Recorder`.

## 2. Start (R-2, V-6, §1.2.3)

`Recorder.start()` is called from the UI while the Activity is visible: from the FAB, or from the V-6 launch once the
core is ready (the splash holds until then).

1. **Guard.** Only `Idle` may start. A second call while `Starting` returns the same pending result.
2. **Grants, before anything Android-visible.** API 31+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`; every API:
   `ACCESS_FINE_LOCATION`. If one is missing, return `Result.failure`, set `RecorderState.error` and stay `Idle`.
   No service starts, so Android 14's `SecurityException` for a `connectedDevice` or `location` service can never
   fire (§6.3 DoD: "a missing grant gives no service start and an error state").
   `POST_NOTIFICATIONS` is not required: without it the service still runs, and only the drawer entry is hidden.
3. **`Starting`**, then `ContextCompat.startForegroundService(RecordingService)`.
4. **The service enters the foreground at once**, in `onStartCommand`, before it awaits anything:
   `ServiceCompat.startForeground(id, "Starting recording", LOCATION | CONNECTED_DEVICE)`. The 5 s
   `startForeground` deadline must never depend on the core opening.
5. **The Recorder waits for the service's answer**, a `CompletableDeferred<Boolean>` completed by `onStartCommand`,
   with a 5 s timeout.
   - **Refused** (`ForegroundServiceStartNotAllowedException`, `SecurityException`, or timeout): the service stops
     itself, the Recorder returns to `Idle` with `error`, and **no core session was created**. v3 started the
     session first and kept recording without the foreground guarantees; v2 does not, so there are no orphan
     sessions.
6. **Session.** `core = coreDeferred.await()`, then `core.startRecording(scanEnvironment())`, all on
   `Dispatchers.IO`. On error, stop the service and return to `Idle` with `error`. `AlreadyRecording` cannot happen
   after a restart, because `Core::open` marks a left-open session `Interrupted`. If it happens anyway, the Recorder
   adopts `core.recordingState()`.
7. **Collect.** Start the RECORDING location request (§4.2), then the scanner (§4.1). Set `Recording`.
   - With Bluetooth off, the scanner is deferred: the session stays open, `scannerDeferred = true`, and the
     `ACTION_STATE_CHANGED` receiver restarts it on `STATE_ON` (v3 behaviour, §4.1).
   - With the location provider off, the request still registers. The status says `Off`, and `clearLocation()`
     keeps lat/lon null in the Parquet.
8. Return `Result.success(sessionId)`.

## 3. Pause and resume (R-6, owner decision "Pause")

**Pause** keeps the session and the foreground service:

1. Stop the scan registration, and wait for the main-looper callback fence (v3 `CallbackFence`). No scan callback
   that is already queued can then reach the batcher after this point.
2. Flush the batcher. Its pending batch goes to `ingestScans` while the core is still recording. Otherwise the last
   ≤ 250 ms would come back as `ignored_not_recording`.
3. `core.pauseRecording()` ([W]: append + commit).
4. Stop the RECORDING location request. If the UI is started, the FOREGROUND request takes over for `myLocation`
   and the status; it never feeds the core.
5. Set `Paused`. The service **stays in the foreground**, releases the wake lock (nothing is collected), and shows
   "Recording paused" with Resume and Stop.

**Resume** reverses it: `core.resumeRecording()`, then RECORDING location, then the scanner, then `Recording`.

- Resume works from the notification with the app in the background. It needs no new foreground-service start, and
  the while-in-use location access granted when the service started at step 2.4 is still in force. This is why
  pause must never drop the service.
- Elapsed time comes from the core's `RecordingState` (`active_before_ms` + `now − running_since_ms`). The Recorder
  never keeps its own clock (v3 did), so the paused time is excluded in exactly one place.

## 4. Stop (R-7)

1. Set `Stopping`. Stop the scanner, wait for the fence and flush the batcher, as for pause.
2. `core.stopRecording()` ([W]: append + commit, seal, simplify the route). Returns the session id.
3. Stop the RECORDING location request.
4. Set `Idle` (keeping `sessionId` for V-3). The service observes `Idle`: `stopForeground(REMOVE)`, `stopSelf()`,
   and it releases the wake lock.
5. The core emits `UploadNeeded`, and `Uploads` enqueues the worker.

The wake lock and the service last until step 2 returns, so sealing can finish with the screen off (v3
`finalizing`).

## 5. Survival

- **The task is swiped away.** The manifest keeps the default `stopWithTask="false"`, and the service does not
  override `onTaskRemoved`. The process keeps foreground-service priority, so scanning, location, the batcher and
  the core's threads continue.
- **The Activity is destroyed or the configuration changes.** Nothing in `:platform` holds an Activity. The scan
  callback and the location callback are bound to the application context and the main looper.
- **Screen off.** The partial wake lock is held while `Recording && !scannerDeferred`, bounded at 12 h (§1.2.3) and
  re-acquired on each resume.
  - Android pauses unfiltered scans with the screen off. The single empty `ScanFilter` covers API ≤ 34, and the
    foreground service covers the rest (v3 comment, kept).
  - The 60 s restart is skipped while the screen is off, until the scan has run 20 min (v3 `ScanRestartPolicy`,
    because of a Note10 that delivers nothing to a scan started with the screen off). `ACTION_SCREEN_ON` applies a
    held-back restart or a Coded PHY change at once.
- **Process death** (an OEM killer, OOM, or a crash) is the only thing that ends a recording without Stop.
  - The FGS notification goes with the process.
  - On the next start, once the core is open, the platform reads `CoreQueries.sessions()`. For each `Interrupted`
    session it has not acknowledged (a prefs set, as in v3 `InterruptedRecordingNotifier`), it posts the one-time
    "Recording interrupted" notice on the `recording-alerts` channel, then marks them all seen.
- **No automatic restart.** `START_NOT_STICKY` (§1.2.7).
  - A notification action that arrives with the Recorder `Idle`, for example a stale `PendingIntent`, makes the
    service `stopSelf()` without entering the foreground.
  - Actions use `PendingIntent.getService`, which is allowed from a notification while the service is already in
    the foreground.

## 6. Notifications

| Channel | Importance | Content |
|---|---|---|
| `recording` | LOW, `setOnlyAlertOnce` | "Starting recording"; "Recording · 12:34 · 26 devices"; "Recording paused · 12:34 · 26 devices"; "Recording waiting for Bluetooth"; "Saving recording". Actions Pause/Resume and Stop; tapping opens the launcher activity. |
| `recording-alerts` | DEFAULT, auto-cancel | "Recording interrupted" (one per launch, covering every unseen interrupted session) |

- **Update rate.** At most every 5 s, or at once on a phase change (§4.4).
- **Device count.** `RecordingState.device_count`, which the core already counts with the display filter (G-12).
- **Opening the app.** The tap intent is the launcher intent of the host package
  (`packageManager.getLaunchIntentForPackage`). `:platform` therefore never names `MainActivity`, and `:gallery`
  works too.
- **Small icon.** A monochrome vector owned by `:platform`.

## 7. Threads (§1.3)

| Work | Thread |
|---|---|
| `ScanCallback`, `LocationCallback`, BT/screen receivers | main; each converts and hands off without blocking |
| `ScanIngest` consumer: batch ≤ 64 or 250 ms → `ingestScans` | `Dispatchers.IO.limitedParallelism(1)` |
| `Recorder` transitions | one `Mutex`, on `Dispatchers.IO` (the core calls are [W]) |
| Service notification and wake lock | main, collecting `Recorder.state` |

`Backpressure` keeps the batch and retries after 100 ms. The inbound `Channel(1024, DROP_OLDEST)` absorbs bursts, and
its drops are counted in `RecorderState.dropped`. Unlike v3, the scanner itself is never paused for backpressure.

## 8. Upgrade from v3 (§1.5)

The order is the owner's decision:

1. The first v2 launch records `pending_uploads()` ids as `legacy_pending` (prefs).
2. The upload worker drains them.
3. When none of them is still pending, delete `databases/local-data.db` with `-wal`, `-shm` and `-journal`.

The pre-0.0.5 JSONL uploader (`*.jsonl.zst` in `filesDir`) is ported unchanged into the same worker.

## 9. Decisions (coordinator, 2026-10-28)

- §2 step 5 (wait for the foreground confirmation; no session on refusal) and the flush before pause and stop are
  approved. `docs/v2-api.md` §4.4 now states both.
- `Settings.onboardingDone: Boolean = false` is set by Done in O-2 and not migrated, so v3 upgraders see the v2
  onboarding once (§4.5).
- No GATT target gives a failed Connecting step with `Other(GATT_NO_TARGET)` (−5), next to `GATT_UNAVAILABLE` (−4).
  Both are named constants in `:platform-api` (§4.3).
- `ExportShare.share()` acknowledges the previous shared export at the next export or the next app start;
  `saveToDownloads()` acknowledges right after the copy (§4.5).
