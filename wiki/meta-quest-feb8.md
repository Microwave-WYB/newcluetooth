---
qmd:
  metadata:
    title: "Meta Quest* headsets (FEB8)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Meta `Quest*` headsets (`FEB8`)

## How to recognize it

- **Local name:** `Quest*`
- **Company field:** company field 1422
- **Service / service-data UUID:** `FEB8`
- **AD shape (ordered type/data length):** `01,09,03,FF,16`
- **What it is:** Meta Quest 2 / 3 / 3S name tokens.
- **Confidence and limits:** Candidate features, not identity authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Meta Quest VR / mixed-reality headsets

The `Quest*` cohort has **26 variants / 26 addresses / 175 scans**. [Meta's Quest device comparison](https://developers.meta.com/horizon/essentials/compare-devices/) corroborates the three name-model candidates. Match `Quest 3` with a model-token boundary so it does **not** include `Quest 3S`.

| Name-model token | Variants / addresses / scans |
|---|---:|
| `Quest 2` | **1 / 1 / 1** |
| `Quest 3` (not `3S`) | **11 / 11 / 137** |
| `Quest 3S` | **14 / 14 / 37** |

All groups carry service/service-data UUID `FEB8` and auxiliary company field 1422; ordered AD types `01,09,03,FF,16` with name-data length 7 or 8. Name and UUID are **candidate features, not identity authentication** or proof the headset was running.
