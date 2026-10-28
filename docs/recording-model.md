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

Android pauses unfiltered BLE scans when the screen turns off. The scanner sends one empty
`ScanFilter`, which only helps up to API 14 (AOSP 15 treats an all-empty filter as unfiltered).
The reliable path is a recording: the foreground service keeps the process and the scanner
alive. A scan that is merely started with the screen off delivered nothing on a Galaxy Note10,
so the once-a-minute scan restart waits while the screen is off (bounded at 20 minutes) and a
screen-on receiver applies it, and a changed Coded PHY setting, immediately.

## Bonding

Android may start pairing by itself when a GATT read fails for authentication or encryption;
there is no public API to forbid it. The app's code never bonds: it calls no pairing or
bonding API and never writes or subscribes. It records the failure status and reads on; the
operator cancels any system dialog.
