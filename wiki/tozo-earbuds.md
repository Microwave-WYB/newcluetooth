---
qmd:
  metadata:
    title: "TOZO TOZO-T* / TOZO-A* earbuds"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# TOZO `TOZO-T*` / `TOZO-A*` earbuds

## How to recognize it

- **Local name:** `TOZO-T*`, `TOZO-A*`
- **Company field:** company field 64600
- **AD shape (ordered type/data length):** `09,FF`, `FF/9,09/8`
- **What it is:** TOZO T6, T10 and A1 earbud name tokens.
- **Confidence and limits:** Model-name candidates; company field 64600 is not brand authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## TOZO T-series true-wireless earbuds

The `TOZO-T*` cohort contains **11 exact `(addr,raw)` variants / 11 addresses / 230 scans**. Complete public name tokens distinguish two [TOZO](https://www.tozostore.com/) in-ear earbud model candidates; both mainly show ordered `09,FF`, auxiliary company field 64600 (not brand authentication), and no advertised service UUID:

| Advertised public model token / original manufacturer product | Variants / addresses / scans |
|---|---:|
| [`TOZO-T6` → T6](https://www.tozostore.com/products/t6) | **6 / 6 / 147** |
| [`TOZO-T10` → T10](https://www.tozostore.com/products/t10) | **5 / 5 / 83** |

The name-column length sometimes differs from the AD name-data length; no repair or hardware-revision inference is made. Advertisement names do not authenticate products or count complete earbud sets, and current retail specifications do not prove the observed generation.

A **separate** `TOZO-A*` cohort has **6 exact variants / 6 addresses / 60 scans**, all with the public pairing name `TOZO-A1`, ordered `FF/9,09/8` and no advertised service UUID. [Original TOZO A1 Mini product page](https://www.tozostore.com/products/a1) and indexed pairing name support the A1 Mini earbud model candidate, not hardware/firmware revision or authentic unit counts. These six observations are **not** included in the T6/T10 counts above.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
