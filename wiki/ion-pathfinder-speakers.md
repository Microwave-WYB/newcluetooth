---
qmd:
  metadata:
    title: "ION Audio Pathfinder* speakers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# ION Audio `Pathfinder*` speakers

## How to recognize it

- **Local name:** `Pathfinder*`
- **Service / service-data UUID:** `1812`
- **AD shape (ordered type/data length):** `01/1,03/2,FF/3,09/<name length>`
- **What it is:** ION Audio Pathfinder 4 / 280 outdoor speakers (`_BLE` suffix).
- **Confidence and limits:** Model-name candidates; `1812` alone does not identify ION. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## ION Audio Pathfinder outdoor speakers

Five exact `Pathfinder*` observations (**5 variants / 5 addresses / 15 scans**) advertise two complete public product-name tokens with `_BLE` suffix. Both use generic HID service UUID `1812` and ordered `01/1,03/2,FF/3,09/<name length>`; that UUID alone does not identify ION or a speaker:

| Sanitized model token / original manufacturer guide | Variants / addresses / scans |
|---|---:|
| [`Pathfinder 4` + `_BLE`](https://support.ionaudio.com/en/support/solutions/articles/69000800442-ion-audio-pathfinder-4-frequently-asked-questions) | **3 / 3 / 8** |
| [`Pathfinder 280` + `_BLE`](https://support.ionaudio.com/en/support/solutions/articles/69000800441-ion-audio-pathfinder-280-frequently-asked-questions) | **2 / 2 / 7** |

Model-name tokens support two ION Audio outdoor-speaker candidates, not authenticated hardware, independent unit counts or any inferred pet-tracking/navigation function.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
