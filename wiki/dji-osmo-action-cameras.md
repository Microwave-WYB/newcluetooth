---
qmd:
  metadata:
    title: "DJI Osmo Action OsmoAction* cameras"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# DJI Osmo Action `OsmoAction*` cameras

## How to recognize it

- **Local name:** `OsmoAction*`
- **Company field:** company field 2218
- **Service / service-data UUID:** `FFF0`
- **What it is:** DJI Osmo Action-series cameras identified by the model-name token.
- **Confidence and limits:** Model-name candidates; `FFF0` is generic and not DJI-exclusive. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## DJI Osmo Action cameras

Five `OsmoAction*` observations represent **5 variants / 5 addresses / 36 scans**. Public model-name tokens plus auxiliary DJI company field 2218 support three [DJI Osmo Action-series](https://www.dji.com/products/comparison-action) camera candidates; all also advertise generic service UUID `FFF0`, which is **not DJI-exclusive**.

| Sanitized model token / original product source | Variants / addresses / scans |
|---|---:|
| [`OsmoAction3` → Osmo Action 3](https://dl.djicdn.com/downloads/DJI_Osmo_%20Action_%203/UM/0930/DJI_Osmo_Action_3_User_Manual_v1.0_en.pdf) | **1 / 1 / 4** |
| [`OsmoAction4` → Osmo Action 4](https://www.dji.com/support/product/osmo-action-4) | **2 / 2 / 12** |
| [`OsmoAction5Pro` → Osmo Action 5 Pro](https://www.dji.com/products/comparison-action) | **2 / 2 / 20** |

The device suffixes remain hidden; neither BLE shape nor service UUID authenticates a camera, determines a lens/firmware version or proves it was filming.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
