# Cluetooth payload schema v3 and gatt/v1

Status: the consumer (sync) side of the design in issue #23. It extends
`docs/payload-schema-v2.md`; everything not mentioned here (identity, object
path rules, encryption, Zstd pages, batching) is unchanged. Producer and
consumer are built in parallel, so the points where #23 left room are marked
**decision** and are repeated in the pull request.

## Object paths

```text
scans/v3/YYYY/MM/DD/<uuidv7>.parquet.encrypted
gatt/v1/YYYY/MM/DD/<uuidv7>.json.encrypted
```

Both follow the v2 rules: `<uuidv7>` is a canonical lowercase hyphenated UUIDv7
and `YYYY/MM/DD` is the UTC date encoded by it. The identity is repeated in the
file (`cluetooth.payload_id` in the Parquet footer, `id` in the JSON) and must
match the path. Any other path stays rejected and recorded as failed:
`scans/v1/`, `scans/v4/`, `gatt/v2/` (and any `<root>/v<N>/`) as an unsupported
schema, everything else under `scans/v3/` or `gatt/` as an unsupported path.

## scans/v3 (Parquet)

The ten v2 columns, in the same order with the same rules, then these columns,
all nullable, in this order:

| Position | Column | Type | Meaning |
| ---: | --- | --- | --- |
| 11 | `is_legacy` | Boolean | legacy vs extended PDU |
| 12 | `primary_phy` | Int32 | 1 = 1M, 3 = Coded |
| 13 | `secondary_phy` | Int32 | 0 = unused, 1 = 1M, 2 = 2M, 3 = Coded |
| 14 | `adv_sid` | Int32 | advertising set ID, 0 to 15 |
| 15 | `adv_tx_power` | Int32 | TX power of the extended header (`tx_power` stays the AD `0x0A` value) |
| 16 | `periodic_interval` | Int32 | periodic advertising interval in 1.25 ms units, 1 to 65536 |
| 17 | `data_status` | Int32 | 0 complete, 1 or 2 truncated (see below) |
| 18 | `addr_type` | Int32 | 0 = public, 1 = random where the platform reports it |

The file must have exactly these 18 columns and dtypes, in this order. `raw` may
be up to 1650 bytes. The value rules of v2 apply to the first ten columns
(addr form, ranges, finite and paired coordinates).

Value rules for the new columns:

- `primary_phy` is 1 or 3; `secondary_phy` 0 to 3; `adv_sid` 0 to 15;
  `adv_tx_power` -127 to 126; `periodic_interval` 1 to 65536; `data_status` 0 to
  2; `addr_type` 0 to 255. Anything else fails the whole file.
- **Decision: absent-value sentinels become null.** Android reports "absent" in
  band: `rssi` 127 (already so in v2), `adv_sid` 255, `adv_tx_power` 127 and
  `periodic_interval` 0. The producer should write null, and the consumer maps
  these values to null in case it does not; it is lossless.
- **Decision: `data_status` accepts 0, 1 and 2.** #23 says "0 complete, 1
  truncated". Android's `ScanResult.DATA_TRUNCATED` is, as far as I recall, `2`,
  so both are accepted and stored verbatim rather than rejecting truncated
  sightings. To be pinned down on the producer side.
- **Decision: `addr_type` codes.** #23 does not give the Int32 codes. 0 and 1
  follow `BluetoothDevice.ADDRESS_TYPE_PUBLIC`/`_RANDOM`; other values up to 255
  are stored verbatim.

### Footer metadata

`cluetooth.payload_schema = v3` and `cluetooth.payload_id` are required exactly
as in v2. **Decision: every other `cluetooth.*` entry is stored** in
`blobs.meta` as a JSON object of strings, key without the `cluetooth.` prefix;
non-`cluetooth.` entries (for example Arrow's own) are ignored. A `cluetooth.*`
key may occur only once and must have a value. Nothing besides schema and id is
required, so a producer that names a key differently loses no data and does not
get its files rejected. The consumer reads these names:

| Key | Content | Used for |
| --- | --- | --- |
| `cluetooth.scan_session_id` | session UUIDv7 (as in v2) | `blobs.session_id` and the minute row's `session_id` |
| `cluetooth.app_version` | app version | `blobs.app_version` |
| `cluetooth.device_model` | device model | `blobs.meta` |
| `cluetooth.android_version` | Android version | `blobs.meta` |
| `cluetooth.le_extended_advertising_supported` | `true`/`false` | `blobs.meta` |
| `cluetooth.le_coded_phy_supported` | `true`/`false` | `blobs.meta` |
| `cluetooth.le_max_advertising_data_length` | integer | `blobs.meta` |
| `cluetooth.scan_phy` | scan PHY setting in effect | `blobs.meta` |

For example `select meta ->> 'le_coded_phy_supported' from blobs where ...` tells
whether the absence of Coded PHY sightings in a file means anything. A valid
empty file is accepted and its metadata is stored.

### Aggregation key

**Decision.** `sighting_minutes` is keyed by

```text
(minute, addr, payload_id, uploader_id, session_id,
 addr_type, is_legacy, primary_phy, secondary_phy, adv_sid, data_status)
```

unique with nulls not distinct. The first five are the v2 key. The other six say
which kind of advertisement a sighting was; the payload (the bytes) cannot, because the same
bytes can be received as a legacy PDU, as an extended PDU on the 2M PHY and on
Coded PHY from the same address in the same minute, and different sets of one
device (`adv_sid`) can carry identical data. Merging those into one row would
lose exactly what extended advertising adds, and the old "first non-null wins"
merge of these columns depended on the order of ingestion, so it could not be
rebuilt exactly. With them in the key every column stays a sum, min, max or
best sample, and `cluetooth rebuild` reproduces the rows.

- Cost: a device that really changes PDU kind in a minute gets one row per kind;
  everything else is unchanged. Legacy and v2 sightings have null in all six, and
  the key treats nulls as equal, so their keys are what they were. Sightings of a
  producer that does not report a field (null) stay apart from those that do.
- `adv_tx_power` and `periodic_interval` are measurements of the set, not part of its identity, and are
  **not** in the key. They merge as the maximum (like `tx_power`), which is exact and
  independent of order. Per-minute rows still show a change over time.
- `adv_length` (reserved in the minute index) is not filled: it equals
  `octet_length(payload_raw(payload_id))`.
- Not payload-level: a payload row is one exact byte string; the PDU fields are
  properties of a reception.

## gatt/v1 (JSON)

One UTF-8 JSON object per discovery, sealed-box encrypted like scans. The shape
is the one in #23. Validation is strict about types and values and tolerant
about absence:

- Required: `schema` (`"gatt/v1"`), `id` (equal to the path UUID),
  `target.addr` (canonical uppercase MAC), `started_at`, `outcome`,
  `stage_reached`, `services` (an array, empty if nothing was discovered).
- Every other key may be absent or null; if present it must have the type and range below. Unknown keys are allowed and kept in the stored JSON.
  Duplicate keys, `NaN` and `Infinity` are rejected.

| Key | Rule |
| --- | --- |
| `session_id` | UUID |
| `app_version`, `device.model`, `device.android` | strings |
| `target.addr_type` | `public`, `random`, `anonymous`, `unknown` |
| `target.name` | string, kept only in the stored JSON (names come from the advertisement) |
| `target.trigger_adv` | optional; `raw` is non-empty even-length hex; `rssi`, `scanned_at` (ISO-8601 with offset), `is_legacy`, `primary_phy` (1 or 3), `secondary_phy` (0 to 3), `adv_sid` (0 to 15; 255 is null) |
| `started_at`, `ended_at` | ISO-8601 with an offset, stored as UTC; `ended_at` not before `started_at` |
| `location` | `lat`/`lon` paired, finite, in range; `accuracy` needs coordinates, finite, nonnegative |
| `outcome` | `complete`, `partial`, `failed`, `cancelled` |
| `stage_reached` | `connect`, `mtu`, `discover`, `read`, `disconnect` |
| `error` | `stage` (a stage name), `status` (integer), `name` (string) |
| `disconnect` | `initiator` (`local`, `remote`, `timeout`), `status` (integer) |
| `mtu` | 23 to 517 |
| `phy` | `tx`, `rx`: 1 to 3 |
| `timings_ms` | `connect`, `mtu`, `discover`, `read`: nonnegative integers |

Services have `uuid` (required), `primary` (boolean), `instance` (nonnegative
integer) and `characteristics`; characteristics have `uuid`, `instance`,
`properties` (lowercase names), `value` and `descriptors`; descriptors have
`uuid` and `value`. A `value` is `{"hex", "status"}`:

- `status` is `ok`, `insufficient_authentication`, `insufficient_encryption`,
  `read_not_permitted`, `timeout`, `not_read` or `error:<n>`.
- `ok` requires `hex` (even-length hex, at most 512 bytes, empty allowed). Any
  other status requires `hex` to be absent or empty.
- An absent `value` is stored as `not_read`.

**Decision: UUID notation.** UUIDs are stored lowercase and a Bluetooth Base
UUID is shortened to 4 hex digits (16-bit) or 8 (32-bit) digits, which is the
notation of `ad_structures.uuids`, so GATT services join the service UUIDs
of advertisements. `180A`, `180a` and `0000180a-0000-1000-8000-00805f9b34fb` are
the same service.

### profile_hash

`gatt_discoveries.profile_hash` is the hex SHA-256 of the compact JSON
(`separators=(",", ":")`) of the structure without values:

```text
[[service_uuid, [[characteristic_uuid, [property, ...], [descriptor_uuid, ...]], ...]], ...]
```

in document order, with each characteristic's properties sorted and unique, UUIDs
normalized as above. Values, read statuses, instance ids, `primary`, MTU and the
outcome are not part of it, so a partial and a complete discovery of the same
device, or two devices with the same GATT database, hash equally. It is null
when no service was discovered.

## Compatibility

Sync-first, as for v2: deploy the migration and this consumer before a
producer writes `scans/v3/` or `gatt/v1/`. Without the consumer the objects are
rejected, recorded as failed and retried with backoff; nothing is lost because
the objects are never deleted. `cluetooth sync` discovers `scans/` and `gatt/`
by default (`--prefix` is repeatable; `CLUETOOTH_PREFIX` takes space-separated
values).
