---
qmd:
  metadata:
    title: "CAREL Bluetooth HVAC/R control family candidate"
    date: "2026-10-16"
    tags: [iot, ble, hvac]
    confidence: medium
---

# CAREL Bluetooth HVAC/R control family candidate

## Identification and use

The sanitized `CAREL_*` name pattern plus first-party BLE company field **1458 (CAREL INDUSTRIES S.P.A.)** supports a CAREL HVAC/refrigeration control **family candidate**. Read-only `cluetooth` aggregates researched **2026-10-16** found **21 exact `(addr,raw)` variants / 21 observed addresses / 106 scans**; the ordered AD types/data lengths are `01/1, FF/4, 09/16, FF/10`. These counts are not physical-device counts or observation dates. No service UUID was observed.

[CAREL's digital-services page](https://iot.carel.com/digital-service) describes Bluetooth-enabled local setup/maintenance with APPLICA/CONTROLLA. **This still does not identify a controller or gateway model.** In particular, the [serial-to-Bluetooth gateway document](https://www.carel.com/documents/10191/0/%2B050002725/46dd3ff6-5715-4cf1-9fa1-0f6a148344fc?version=1.4) uses a *longer* `CAREL_GTW_SERIAL2BT_*` name, so its hardware must not be substituted for these 16-byte names. Neither company field nor local name authenticates a device; no hospital deployment, ownership, telemetry or vulnerability is inferred. No active GATT interaction was performed.

## Sources

- [CAREL Bluetooth/wireless gateway — original manufacturer page](https://healthcare.carel.com/gateway-wireless/-/journal_content/56_INSTANCE_i4q5KIMLInKK/10191/1005572).
- Private research: `hospital_iot_candidates_2026-10-14.md`, CAREL row.
  No dated Wayback fallback was verified for the manufacturer URL.
- First-party read-only `cluetooth.adv_shapes` query, 2026-10-16; see [broader catalogue](first-party-ble-fingerprints-2026-10-16.md). Raw addresses and payloads remain outside Git.
