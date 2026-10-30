# :map (lane E)

MapLibre Android with the shared overlay style (`design/ui-v2/map-style.json`, copied into the assets at build time),
the camera rules, selection, taps and the locate control. See `docs/v2-api.md` §6.6 and SPEC M, P-4, P-5, T-8.

**One MapLibre renderer at a time.** The app has exactly one `MapView` (`CluetoothMap`) and no `MapSnapshotter`:
a second native renderer next to the main map crashed MapLibre 12.3.1 natively (SIGSEGV in
`MapSnapshotter::Impl::onDidFinishLoadingStyle` once a snapshotter was collected while its style loaded), so SPEC
R-5's mini map was removed (design a201027). `scanner/tools/single-renderer-check` fails the build on a
`MapSnapshotter` or a second `MapView` in any `src/main`.

## Contract notes

- **Session framing.** `show()` with a new scope frames that session itself, from `SessionView.frame`, without
  animation (M-10, V-7). `:app` does not call `frame()` on a session switch; `frame()` is only the animated D-8
  framing when a product sheet opens.
- **GeoJSON `addr`.** The core writes it in uppercase colon form (`AA:BB:CC:DD:EE:FF`); `DeviceAddr` maps it to and
  from `DeviceKey`.
- **M-11.** The moved-away test is the one copy in `:ui-contract` (`movedAway`, `viewportCamera`). `:map` has no
  caller of its own: it supplies the inputs, `MapEvents.onCameraIdle(byUser, viewport, zoom)` with the visible
  bbox, and `MapGeometryTest` checks the shared function against that viewport in the real view's screen space.
- **Loaded area.** `map_points` serves the viewport + 50 %, a quarter of the width and height on each side; an idle
  camera inside that area (in Points mode) does not re-query.

## Tests

- JVM tests and lint: `mise run //scanner:map-test`.
- Instrumented tests run on the `cluetooth-test` AVD only. Build the test APK and install it on the emulator by serial;
  do not use `connectedAndroidTest`, which installs on every attached device:

  ```
  ./gradlew --no-daemon :map:assembleDebugAndroidTest
  adb -s emulator-5564 install -r -t map/build/outputs/apk/androidTest/debug/map-debug-androidTest.apk
  adb -s emulator-5564 shell am instrument -w edu.ucsd.sysnet.cluetoothscanner.map.test/androidx.test.runner.AndroidJUnitRunner
  ```

  `MapFrameTimeTest` takes `-e blank true` to pan over a blank base map instead of OpenFreeMap.
