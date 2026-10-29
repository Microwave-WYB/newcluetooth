# :map (lane E)

MapLibre Android with the shared overlay style (`design/ui-v2/map-style.json`, copied into the assets at build time),
the camera rules, selection, taps and the R-5 mini map. See `docs/v2-api.md` §6.6 and SPEC M, P-4, P-5, T-8.

- JVM tests and lint: `mise run //scanner:map-test`.
- Instrumented tests run on the `cluetooth-test` AVD only. Build the test APK and install it on the emulator by serial;
  do not use `connectedAndroidTest`, which installs on every attached device:

  ```
  ./gradlew --no-daemon :map:assembleDebugAndroidTest
  adb -s emulator-5564 install -r -t map/build/outputs/apk/androidTest/debug/map-debug-androidTest.apk
  adb -s emulator-5564 shell am instrument -w edu.ucsd.sysnet.cluetoothscanner.map.test/androidx.test.runner.AndroidJUnitRunner
  ```

  `MapFrameTimeTest` takes `-e blank true` to pan over a blank base map instead of OpenFreeMap.
- `stub/Contracts.kt` holds temporary copies of the `:ui-contract` and `:platform-api` types until those merge.
