---
qmd:
  metadata:
    title: "Shokz LE-OpenRun* / OpenSwim* bone-conduction headsets"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Shokz `LE-OpenRun*` / `OpenSwim*` bone-conduction headsets

## How to recognize it

- **Local name:** `LE-OpenRun*`, `OpenSwim*`
- **Company field:** company field 3244
- **Service / service-data UUID:** `180F`
- **AD shape (ordered type/data length):** `03,09,FF`, `01,03,09,FF`, `01/1,FF/8,09/21`
- **What it is:** Shokz OpenRun Pro and OpenSwim Pro name tokens.
- **Confidence and limits:** Model-name candidates; `180F` is the generic Battery Service. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Shokz bone-conduction sports headset

All **40 / 40 / 713** `LE-OpenRun*` observations have the sanitized exact model-name token `LE-OpenRun Pro`, supporting a [Shokz OpenRun Pro](https://shokz.com/pages/openrunpro) candidate. Most have ordered AD types `03,09,FF` (37 / 37 / 675); three add flags `01` (3 / 3 / 38). Advertised `180F` is the generic Battery Service, **not** a Shokz-specific identifier. The name alone does not distinguish Standard/Mini fit, generation or revision; no separate OpenRun Pro 2 cohort was found.

The separate `OpenSwim*` cohort has **5 exact `(addr,raw)` variants / 5 addresses / 103 scans**, all with visible model token `OpenSwim Pro` (device-specific suffix hidden), Shokz-assigned company field 3244 and ordered `01/1,FF/8,09/21`. [Shokz's original support page](https://shokz.com/pages/openswimpro-support) distinguishes **OpenSwim Pro** (Bluetooth 5.4 and local MP3 storage) from the original non-Bluetooth **OpenSwim**. Bluetooth does not work underwater: this is neither evidence of underwater streaming nor proof of swimming activity or a pool sensor.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
