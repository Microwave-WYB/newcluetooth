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
| `cluetooth.android_version` | release and API level, e.g. `15 (API 35)`, 1-256 bytes |
| `cluetooth.app_version` | the producer's `versionName`, 1-256 bytes |
| `cluetooth.le_extended_advertising_supported` | `true` / `false` (`isLeExtendedAdvertisingSupported`) |
| `cluetooth.le_coded_phy_supported` | `true` / `false` (`isLeCodedPhySupported`) |
| `cluetooth.le_max_advertising_data_length` | decimal bytes (`getLeMaximumAdvertisingDataLength`; `0` if unreported) |
| `cluetooth.scan_phy` | `all_supported` (`PHY_LE_ALL_SUPPORTED`, Coded where supported) or `1m` (the Coded PHY switch is off) |
| `cluetooth.scan_legacy_only` | `true` / `false`; the producer scans with `setLegacy(false)`, so `false` |

`cluetooth.scan_session_id` stays optional and diagnostic as in v2. Absence of
a pattern in a file (no Coded PHY, no extended PDUs, no long payloads) must be
read against these keys: for example a device with
`le_coded_phy_supported=false` cannot have produced `primary_phy=3`.

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
- `error`, `disconnect`, `mtu`, `phy` and `timings_ms` are `null` when not
  applicable. `error.status` is the Android `BluetoothGatt` status integer
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

The core validates all of the above on `save_gatt_discovery` and refuses
anything else before storing it. Storing the same bytes again is a no-op;
different bytes for an existing id are refused.

## Deviations from #23

| #23 says | This contract | Reason |
| --- | --- | --- |
| `data_status`: 0 complete, 1 truncated | `0` complete, `2` truncated | Android's `ScanResult.DATA_TRUNCATED` is `2` (`1` is not a value Android reports); storing the platform value avoids a lossy remap |
| `target.trigger_adv` example only shows some fields | `raw`, `rssi`, `scanned_at`, `is_legacy`, `primary_phy`, `secondary_phy`, `adv_sid`, each nullable | matches the example; extra fields would be unverifiable from the engine |
| descriptors have no `instance` in the example | descriptor `instance` is always `null` | Android exposes no descriptor instance id |
| `error` / `disconnect` always present | nullable | a successful discovery has neither |
| footer facts "once per file" | enforced by sealing on any change | keeps a file's footer true for every row |
