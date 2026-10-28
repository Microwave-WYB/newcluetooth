# Cluetooth payload schema v3 and GATT discovery v1

Status: authoritative producer/consumer contract for scan payload schema `v3`
and the `gatt/v1` discovery object. It extends `docs/payload-schema-v2.md`
(identity rules, batching, durability, encryption and upload state are
unchanged unless stated here) and implements the collection side of design
issue #23 (work item #24). Server ingestion is tracked separately (#25).

Deviations from the design issue are listed in
[Deviations from #23](#deviations-from-23); read them before writing a
consumer.

## Scan payload v3

### Identity and object path

Same rules as v2 with the schema segment changed:

```text
scans/v3/YYYY/MM/DD/<uuidv7>.parquet.encrypted
```

`<uuidv7>` is the canonical lowercase hyphenated UUID, `YYYY/MM/DD` the UTC
date encoded by that UUIDv7, and the same identity is stored in the footer.
The producer writes only v3 from the version that introduces it. Pending and
archived v2 files remain valid: they keep `scans/v2/...` as their object path
and are uploaded unchanged. A consumer must route by the object path segment
(`v2` or `v3`) and then require the footer `cluetooth.payload_schema` to agree
with it; the footer never selects a decoder on its own.

### Ordered Parquet schema

The ten v2 columns stay first, in the same order and with the same rules
(`raw` is the exact advertisement bytes; `addr` canonical uppercase MAC;
`rssi`/`tx_power` fit a signed smallint; coordinates paired, finite and in
range; `accuracy` finite, nonnegative and only with coordinates; `addr`,
`scanned_at` and `raw` never null). Eight columns are appended:

| Position | Column | Wire type | Nullability | Meaning and producer rule |
| ---: | --- | --- | --- | --- |
| 11 | `is_legacy` | Boolean | nullable | `ScanResult.isLegacy()`: legacy (`true`) vs extended (`false`) PDU |
| 12 | `primary_phy` | Int32 | nullable | `getPrimaryPhy()`; `1` = 1M, `3` = Coded |
| 13 | `secondary_phy` | Int32 | nullable | `getSecondaryPhy()`; `0` unused, `1` = 1M, `2` = 2M, `3` = Coded |
| 14 | `adv_sid` | Int32 | nullable | advertising set ID, `0`..`15`; `null` when absent (platform `255`) |
| 15 | `adv_tx_power` | Int32 | nullable | extended-header TX power, `-127`..`126`; `null` when absent (platform `127`). `tx_power` stays the AD `0x0A` value |
| 16 | `periodic_interval` | Int32 | nullable | periodic advertising interval in 1.25 ms units, `1`..`65535`; `null` when none (platform `0`) |
| 17 | `data_status` | Int32 | nullable | `getDataStatus()`; **`0` complete, `2` truncated** (see deviations) |
| 18 | `addr_type` | Int32 | nullable | `0` public, `1` random, where the platform reports it; otherwise `null` |

`raw` is at most 1650 bytes (the extended advertising maximum). The producer
maps every platform sentinel to `null` before the core sees it, and the core
rejects the batch if a value is outside the ranges above, so a consumer can
treat these ranges as guaranteed. `null` always means "the platform did not
report this", never "zero". Rows converted from a v2 payload (exports only)
carry `null` in all eight columns.

All columns are Parquet Zstd-compressed and the ordered dtypes are part of the
contract exactly as in v2.

### Footer metadata

Required file-level key/value pairs, each exactly once. They describe the whole
file; the core seals the active payload whenever any of them would change, so
every row in a file was collected under the footer it carries.

| Key | Value |
| --- | --- |
| `cluetooth.payload_schema` | `v3` |
| `cluetooth.payload_id` | the object's UUIDv7 |
| `cluetooth.device_model` | `Build.MODEL`, 1-256 bytes |
| `cluetooth.android_release` | `Build.VERSION.RELEASE`, e.g. `15`, 1-256 bytes |
| `cluetooth.android_sdk` | `Build.VERSION.SDK_INT`, decimal, e.g. `35` |
| `cluetooth.app_version` | the producer's `versionName`, 1-256 bytes |
| `cluetooth.cap.ext_adv` | `true` / `false` (`isLeExtendedAdvertisingSupported`) |
| `cluetooth.cap.coded_phy` | `true` / `false` (`isLeCodedPhySupported`) |
| `cluetooth.cap.max_adv_len` | decimal bytes (`getLeMaximumAdvertisingDataLength`; `31` before API 26, `0` if unreported) |
| `cluetooth.scan.phy` | `all_supported` (`PHY_LE_ALL_SUPPORTED`, Coded where supported) or `1m` (the Coded PHY switch is off) |
| `cluetooth.scan.legacy_only` | `true` / `false`; the producer scans with `setLegacy(false)`, so `false` |

The list is exact: every `cluetooth.*` key above must appear once in a v3 file
and the sync side stores every `cluetooth.*` key it finds, so a later producer
may add keys under the same prefix without a schema bump. The key names follow
the owner's clarification on #23.

`cluetooth.scan_session_id` stays optional and diagnostic as in v2. Absence of
a pattern in a file (no Coded PHY, no extended PDUs, no long payloads) must be
read against these keys: for example a device with
`cluetooth.cap.coded_phy=false` cannot have produced `primary_phy=3`.

### Producer behavior

- The scanner uses `ScanSettings.Builder().setLegacy(false)` and
  `setPhy(PHY_LE_ALL_SUPPORTED)` (or `PHY_LE_1M` when the Coded PHY switch is
  off). Periodic-advertising sync is not used; only the advertised interval is
  recorded.
- `addr_type` comes from `BluetoothDevice.getAddressType()` on API 35+ and is
  `null` on older systems or when the platform reports unknown/anonymous.
- The Rust core takes the footer facts through `configure_scan` before the
  first observation and refuses observations until it has them. It persists the
  facts beside the active-payload manifest, so a payload recovered after
  process death is sealed with the footer it was collected under.

### Compatibility

- Pending v2 files, archived v2 files and a v2 active-payload stage left by an
  older app are discovered, uploaded and recovered under their v2 identity;
  none are rewritten as v3.
- A pending or archived file whose footer schema disagrees with its path
  generation is quarantined like any other invalid file.
- Session exports combine payloads of both generations into one file in the v3
  column set (`null` for v2 rows). The JSONL export gains the eight columns.
- UniFFI API version is `7`.

## GATT discovery `gatt/v1`

One manual GATT discovery is one JSON object, written once when the discovery
ends, sealed with the same sealed-box scheme as scans, and uploaded through the
same WorkManager/Firebase path.

```text
gatt/v1/YYYY/MM/DD/<uuidv7>.json.encrypted
```

`<uuidv7>` is minted by the core (`new_gatt_discovery_id`) when the operator
starts the discovery and is the object's `id`; the date segment is the UTC date
of that UUID. Retries reuse the identity. The plaintext JSON is kept locally
under `pending/gatt/v1/...` until the upload is acknowledged and then under
`archive/gatt/v1/...` for later viewing; the core never deletes it.

### Object

```json
{
  "schema": "gatt/v1", "id": "<uuidv7>", "session_id": "<uuidv7> | null", "app_version": "0.1.0",
  "device": { "model": "Pixel 8", "android": "15 (API 35)" },
  "target": {
    "addr": "AA:BB:CC:DD:EE:FF", "addr_type": "public | random | null", "name": "string | null",
    "trigger_adv": null
  },
  "started_at": "2025-03-25T00:00:01.000Z", "ended_at": "2025-03-25T00:00:09.500Z",
  "location": { "lat": 32.88, "lon": -117.23, "accuracy": 4.5 },
  "outcome": "complete | partial | failed | cancelled",
  "stage_reached": "connect | mtu | discover | read | disconnect",
  "error": { "stage": "read", "status": 19, "name": "GATT_CONN_TERMINATE_PEER_USER" },
  "disconnect": { "initiator": "local | remote | timeout", "status": 19 },
  "mtu": 247, "phy": { "tx": 2, "rx": 2 },
  "timings_ms": { "connect": 800, "mtu": 120, "discover": 1500, "read": 4000 },
  "services": [ {
    "uuid": "180A", "primary": true, "instance": 12,
    "characteristics": [ {
      "uuid": "2A29", "instance": 14, "properties": ["read"],
      "value": { "hex": "4d6963726f", "status": "ok" },
      "descriptors": [ { "uuid": "2901", "instance": null, "value": { "hex": "", "status": "ok" } } ]
    } ]
  } ]
}
```

Rules:

- `trigger_adv` is `null` or the last advertisement the app had for the target:
  `raw` (lowercase hex), `rssi`, `scanned_at`, `is_legacy`, `primary_phy`,
  `secondary_phy`, `adv_sid` (each nullable like the scan columns).
- Timestamps are UTC with exactly millisecond precision:
  `YYYY-MM-DDTHH:MM:SS.mmmZ`. `location` is the most recent fix at the start of
  the discovery or `null`. `session_id` is the scan session running when the
  discovery started or `null`.
- `error`, `disconnect`, `mtu` and `phy` are `null` when not applicable; in
  `timings_ms` a stage that was not reached is `null`. `error.status` is the Android `BluetoothGatt` status integer
  (`133` is the generic `GATT_ERROR`); `error.name` is advisory. A timeout has
  `disconnect.initiator = "timeout"` and `error.status = -1`.
- `outcome`: `complete` read everything readable; `partial` kept some results
  but stopped early (disconnect, timeout or error after discovery began);
  `failed` produced no services; `cancelled` the operator stopped it (partial
  data is kept). `stage_reached` is the last stage entered.
- UUIDs: 16-bit Bluetooth-base UUIDs are uppercase four-digit hex (`180A`);
  all others are lowercase 128-bit hyphenated.
- `instance` is the Android instance id of the service/characteristic. Android
  exposes instance ids, not ATT handles; `instance` of a descriptor is `null`
  because the platform does not expose one.
- `properties` is the subset of `broadcast`, `read`, `write_no_response`,
  `write`, `notify`, `indicate`, `signed_write`, `extended_props` derived from
  the characteristic's property bitmask, in that order.
- `value.status` is `ok`, `insufficient_authentication`,
  `insufficient_encryption`, `read_not_permitted`, `timeout`, `error:<n>`
  (`<n>` the Android status) or `not_read` (not readable, skipped, or the
  discovery ended first). `value.hex` is the lowercase hex of the bytes read
  and is present exactly when `status` is `ok` (an empty read is `""`);
  otherwise `null`.
- The producer never writes, subscribes to notifications, or bonds. A read the
  peer rejects for security is recorded with the matching status and the next
  attribute is read. The Android stack may still show a pairing dialog on an
  authentication failure; the operator cancels it. See the engine notes in
  `cluetooth-android/README.md`.

### Validation rules

The core validates every object on `save_gatt_discovery` and refuses anything
else before it is stored. The rules are at least as strict as the server reader
(`gatt_v1.py`), because an object sync rejects is retried forever; where the
server tolerates a sentinel or a spelling, the producer must already have
written the canonical form.

| Member | Rule |
| --- | --- |
| whole object | at most 8 MiB; valid JSON; no NUL (`\u0000`) in any string or key (the producer strips them from names and error strings; a name that was only NULs becomes `null`) |
| `schema`, `id` | `gatt/v1`; canonical lowercase UUIDv7 that names the object |
| `session_id` | `null` or a UUIDv7 |
| `app_version`, `device.model`, `device.android` | non-empty strings |
| `target.addr` | canonical uppercase `XX:XX:XX:XX:XX:XX` |
| `target.addr_type` | `null` or one of `public`, `random`, `anonymous`, `unknown` (the Android producer writes `public`, `random` or `null`) |
| `target.name` | `null` or a string |
| `trigger_adv.raw` | non-empty lowercase hex of even length |
| `trigger_adv.rssi` | `null` or an integer in -127..126 (127 is the platform's "absent" and is written as `null`) |
| `trigger_adv.primary_phy` | `null`, `1` or `3` |
| `trigger_adv.secondary_phy` | `null` or 0..3 |
| `trigger_adv.adv_sid` | `null` or 0..15 (255 is written as `null`) |
| `trigger_adv.scanned_at`, `started_at`, `ended_at` | `YYYY-MM-DDTHH:MM:SS.mmmZ`; `ended_at` is not before `started_at` |
| `location` | `null`, or finite `lat` -90..90, `lon` -180..180 and `accuracy` >= 0, all present |
| `outcome`, `stage_reached` | the enums above |
| `error.stage` | one of the five stages; `error.status` is an integer within 32 bits |
| `disconnect.initiator` | `local`, `remote` or `timeout`; `disconnect.status` is `null` or a 32-bit integer |
| `mtu` | `null` or 23..517 |
| `phy.tx`, `phy.rx` | 1..3 |
| `timings_ms.*` | `null` or an integer >= 0 |
| service/characteristic `instance` | integer >= 0; a descriptor's `instance` is `null` |
| `uuid` | four uppercase hex digits, or lowercase 128-bit hyphenated |
| `properties` | names from the fixed list above |
| `value.status` | `ok`, `insufficient_authentication`, `insufficient_encryption`, `read_not_permitted`, `timeout`, `not_read` or `error:<n>` |
| `value.hex` | lowercase even-length hex of at most 512 bytes when `status` is `ok`; `null` otherwise |

A value longer than 512 bytes would break the ATT protocol; the engine records
it as `error:13` (`GATT_INVALID_ATTRIBUTE_LENGTH`) instead of clipping it, since
a clipped value would claim to be complete. The engine also records `mtu` and
`phy` only when they are inside the ranges above, clamps instances and timings
at zero, and never lets `ended_at` precede `started_at` when the wall clock
steps back.

Storing the same bytes again is a no-op; different bytes for an existing id are
refused.

## Producer fixtures

`test-fixtures/` (repository root) holds objects written by the producer code
itself, so the sync side can prove that it ingests exactly what the app emits:

- `test-fixtures/scans-v3/2025/03/25/<uuidv7>.parquet`: a plaintext scan v3
  file written by the core's Parquet writer (footer with the session id and the
  full environment, a 1650-byte Coded PHY row, a legacy row with a name and a
  location, a truncated extended row and a row where the platform reported
  nothing).
- `test-fixtures/gatt-v1/2025/03/25/<uuidv7>.json`: `...01` to `...03` are
  hand-written golden objects pinning the serializer's shape;
  `...11` to `...15` are produced by running the real engine against a scripted
  peripheral and the real serializer (complete, remote disconnect mid-read,
  authentication/encryption refusals, status 133 then success, discovery
  timeout).

Each file name is the object's identity and the directory is its UTC date, so a
fixture maps straight onto `scans/v3/...` or `gatt/v1/...`. Tests fail when a
fixture no longer matches what the producer writes; regenerate after an
intended change with `CLUETOOTH_UPDATE_FIXTURES=1` (Rust:
`cargo test --test producer_fixtures`, Kotlin: the `GattFixtureTest` unit test).

## Deviations from #23

| #23 says | This contract | Reason |
| --- | --- | --- |
| `data_status`: 0 complete, 1 truncated | `0` complete, `2` truncated | Android's `ScanResult.DATA_TRUNCATED` is `2`; confirmed by the owner on #23 |
| footer keys `le_*`, `android_version`, `scan_phy` | `cluetooth.cap.*`, `cluetooth.android_release` + `cluetooth.android_sdk`, `cluetooth.scan.*` | owner's clarification on #23 |
| `target.trigger_adv` example only shows some fields | `raw`, `rssi`, `scanned_at`, `is_legacy`, `primary_phy`, `secondary_phy`, `adv_sid`, each nullable | matches the example; extra fields would be unverifiable from the engine |
| descriptors have no `instance` in the example | descriptor `instance` is always `null` | Android exposes no descriptor instance id |
| `error` / `disconnect` always present | nullable | a successful discovery has neither |
| `addr_type` | `0`/`1`/`null`; unknown or anonymous is `null` | owner's clarification on #23 |
| footer facts "once per file" | enforced by sealing on any change | keeps a file's footer true for every row |
