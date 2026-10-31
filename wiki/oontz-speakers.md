---
qmd:
  metadata:
    title: "OontZ OontZ* Angle 3 speakers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# OontZ `OontZ*` Angle 3 speakers

## How to recognize it

- **Local name:** `OontZ*`
- **Service / service-data UUID:** `FFC0`
- **AD shape (ordered type/data length):** `01/1,03/2,FF/14,09/<name length>`
- **What it is:** OontZ Angle 3 Ultra / Angle 3 Pro name tokens.
- **Confidence and limits:** Model-name candidates. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## OontZ portable Bluetooth speakers

The `OontZ*` cohort has **5 exact `(addr,raw)` variants / 5 addresses / 79 scans**. Both groups advertise generic service UUID `FFC0` and ordered `01/1,03/2,FF/14,09/<name length>`; only complete public model tokens support distinguishing the speakers:

| Sanitized advertised model token / original manufacturer product | Variants / addresses / scans |
|---|---:|
| [`OontZ` + `Angle 3U` / Ultra → Angle 3 Ultra](https://www.theoontz.com/products/oontz-ultra) | **3 / 3 / 76** |
| [`OontZ` + `Angle 3 Pro` → Angle 3 Pro](https://www.theoontz.com/products/oontz) | **2 / 2 / 3** |

Device-specific suffixes remain hidden. Shared UUID/AD shape cannot distinguish model generation or authenticate hardware; address count is not a confirmed number of physical speakers.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
