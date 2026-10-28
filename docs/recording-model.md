# Live scanning and recording

Two things used to be one: every scan opened a core session, wrote Parquet rows and
uploaded them. They are now separate (issue #28, for the matcher in #29 and the UI in #26).

| | Live scanning | Recording |
| --- | --- | --- |
| Runs when | the app is in the foreground | the user starts it; keeps going with the screen off or the app in the background |
| Core session | none | opened on start, closed on stop |
| Parquet rows / upload | none | yes, only between start and stop |
| Feeds | `BleScanService.liveScans` (UI nearby list, RSSI history, the matcher's in-memory stores) | the above, plus the core |
| Process kept alive by | the foreground Activity | `RecordingService` (foreground service) |

A scan that is only live writes nothing and uploads nothing, so opening the app no longer
creates sessions. Recording is an explicit act.

## The live stream

`BleScanService.liveScans` is a `SharedFlow<LiveScan>` that emits every advertisement whenever
the scanner runs, whether or not a recording is active. A `LiveScan` carries the address, RSSI
(`null` for the platform's "absent" 127), the wall-clock and monotonic time, the exact raw bytes,
the platform local name, TX power, connectability, the scan v3 fields (`ScanV3Fields`) and the
latest location fix. It has a 512-item buffer that drops the oldest item: a slow collector
loses data, the scanner never waits for it. `scanFlow` (the nearby list's `BleRecord`s) is
derived from it.

Stores that must only persist while a recording is active (the matcher's sightings and
routes) collect `liveScans` and gate on `RecordingController.state` (`mode == RECORDING`);
they do not need a session of their own.

## The recording API

`RecordingController` (`CluetoothApplication.recordingController`) is the whole interface:

- `state: StateFlow<RecordingState>`: `mode` (`IDLE`, `RECORDING`, `PAUSED`), `elapsedMs`
  (recording time only, not paused time) and `rowCount` (rows written since this recording
  started; after stop it keeps the last recording's total).
- `start()`: opens a core session, makes sure the scanner runs and starts writing rows, and
  starts the foreground service.
- `pause()`: stops writing rows (behind a callback fence, so no row is queued afterwards)
  and keeps the session. The foreground service stays up; live scanning goes on while the app
  is in the foreground.
- `resume()`: continues the same session. A paused recording stays paused when the Activity
  reappears.
- `stop()`: stops rows, finishes the session (sealing the open payload and scheduling its
  upload) and releases the foreground service.

The record button of the UI maps to these four functions; nothing else is needed to record.

## Who decides what runs

`ScanSupervisor` owns two facts: whether the app is in the foreground and whether rows are
being written. The scanner and location run while either is true.

- The Activity reports foreground/background (`onActivityStopped`, readiness on resume).
- `beginRowWriting` (recording start/resume) makes sure the scanner runs, then lets rows
  through; `endRowWriting` (pause/stop) turns rows off, drains in-flight callbacks, and stops
  the scanner and location only if the app is in the background too.
- A scanner that stops by itself (Bluetooth off, storage backpressure, failure) is resumed by
  the recording layer when a recording is active, otherwise by the supervisor for live scanning.
- Declaring the footer environment (`configure_scan`) is ordered ahead of the first row of
  a recording, so a payload footer always describes its rows.

## Location

`ScanSupervisor` is the only starter and stopper of location and picks a `LocationProfile`:
`RECORDING` while rows are written (a fix every 2 s, minimum interval 1 s, no distance filter, high
accuracy, and no wait for an "accurate" fix, which otherwise holds a stationary or indoor recording
down to a fix about every 40 s) and `LIVE` otherwise (balanced power, every 10 s). Starting a
recording or ending it re-registers the request at the other profile without dropping the current
fix. `LocationService` uses the Play services fused provider when available and otherwise the platform
GPS and network providers (GPS and network for `RECORDING`, one of them for `LIVE`); the newest fix
wins, except that a less accurate fix from another provider waits 5 s behind a fresher one.

## The foreground service

`RecordingService` is declared with `foregroundServiceType="location|connectedDevice"` and the
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`
permissions. It holds a partial wake lock only while the recording is `RECORDING` (released on
pause and stop, with a 12 hour safety bound), shows an ongoing notification (state, elapsed time,
row count; Pause/Resume and Stop actions) and stops itself when the recording is idle. It owns no
recording logic. Android only allows starting it while the app is visible, which is where the
record button is; a start that Android refuses is logged and the recording continues in the
process without the foreground guarantees. `POST_NOTIFICATIONS` (Android 13+) is requested once
the required permissions are granted; recording works without it, but then the notification
and its actions are hidden.

If the process is killed during a recording the core finalizes the open payload on the next start
and marks the session interrupted.

## Screen off

**Plainly: on Android 15 and newer, and on Android 13/14 phones with a newer Bluetooth
mainline module, a recording started or running with the screen off collects nothing.** Android
stops delivering *unfiltered* BLE scans while the screen is off. Since AOSP 15 a filter list whose
filters are all empty counts as unfiltered, and the foreground service (with its wake lock) is not an
exemption: the process stays alive, but the platform simply sends no scan results. The empty
`ScanFilter` the scanner passes therefore helps only on API 14 and below (and only on builds
that still treat it as a filter).

What was verified: a Galaxy Note10 on Android 12. There a recording kept collecting at the full
rate with the screen off (about 650 rows per 30 s for 143 s), and a plain scan without the
service degraded to about 10% and then nothing. The Android 15 behaviour is from the platform
source, not from a device test.

A broad non-empty filter set does not exist: a filter must name a field (company ID, service
UUID, name, address, ...), and an advertisement that lacks that field is dropped, so any set
that is cheap enough to install would silently lose devices. None is used, because a recording
that quietly misses devices is worse than one that visibly collects nothing. Until that is
solved (for example by a companion approach or per-device filters), on Android 15+ record with
the screen on; the live list and recording work normally with the screen on.

Related: a scan that is merely *started* with the screen off delivered nothing on the Note10
even on Android 12, so the once-a-minute scan restart waits while the screen is off (bounded at
20 minutes) and a screen-on receiver applies it, and a changed Coded PHY setting, immediately.

## Stop, deferral and failures

- **Stop** keeps the foreground service and the wake lock until the core has finished the
  session: `RecordingState.finalizing` is true (and `mode` is already `IDLE`) while the open payload
  is sealed. A finalization that fails is terminal; the service is released and the mode stays idle.
- **Bluetooth off** while recording: `mode` stays `RECORDING` with `scannerDeferred = true`,
  nothing is collected, the wake lock is released, and it is taken again when scanning resumes.
- **The foreground service cannot start** (Android refuses it, for example when the app was not
  visible): the service stops itself and `RecordingState.error` carries the reason. The recording
  carries on inside the process without the foreground guarantees.
- **The process dies mid-recording:** on the next launch the core has finalized the open payload
  and marked the session interrupted, and the app posts one notification per such session
  ("Recording interrupted: N rows saved", in Chinese "上次录制意外中断，已保存 N 条").
- **Rows the core refuses** (an advertisement over 1650 bytes, a malformed address, an
  out-of-range value) are dropped at its input boundary and counted
  (`rejected_observation_count`, `last_rejection`); the scan service also keeps over-long
  advertisements out of the durable path. A bad row can never block a checkpoint or seal.
- **`RecordingState`** gained `finalizing`, `scannerDeferred` and `error` as additive fields with
  defaults; the four control functions and `liveScans` are unchanged.

## Bonding

Android may start pairing by itself when a GATT read fails for authentication or encryption;
there is no public API to forbid it. The app's code never bonds: it calls no pairing or
bonding API and never writes or subscribes. It records the failure status and reads on; the
operator cancels any system dialog.
