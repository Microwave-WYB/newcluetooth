---
qmd:
  metadata:
    title: "Marshall Acton and Stanmore speakers (FE8F, AA00, FE2C)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Marshall Acton and Stanmore speakers (`FE8F`, `AA00`, `FE2C`)

## How to recognize it

- **Local name:** `Acton*`, `Stanmore*`
- **Company field:** company field 1626
- **Service / service-data UUID:** `FE8F`, `AA00`, `FE2C`
- **AD shape (ordered type/data length):** `01/1,03/2,FF/2,09/8`, `01/1,03/2,FF/2,09/11`
- **What it is:** Marshall Acton and Stanmore Bluetooth speakers distinguished by generation name token.
- **Confidence and limits:** Generation candidates from name tokens; not authenticated. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Marshall Acton Bluetooth speakers

The `Acton*` cohort is **13 / 13 / 190**. A distinct II/2 versus III/3 model token, rather than a shared generic shape, supports two [Marshall Acton speaker](https://www.marshall.com/us/en/product/acton-iii) generation candidates:

| Sanitized model token / original product source | Variants / addresses / scans | Auxiliary advertising signal |
|---|---:|---|
| [Acton II Bluetooth](https://www.marshall.com/cz/en/support/speakers/support-for-acton-ii-bluetooth/how-to-connect-and-pair) | **8 / 8 / 48** | `FE8F` service UUID; `01/1,03/2,FF/2,09/8` |
| [Acton III](https://www.marshall.com/us/en/product/acton-iii) | **5 / 5 / 142** | Four variants with `FE2C` service data; one with `AA00` service UUID and auxiliary company field 1626. |

`FE2C` is a generic Fast Pair service, not a Marshall-specific identity. Names and auxiliary UUIDs do not authenticate the device or determine hardware revisions.

## Marshall Stanmore home Bluetooth speakers

The `Stanmore*` cohort is **8 exact `(addr,raw)` variants / 8 addresses / 96 scans**. Public generation tokens distinguish two **mains-powered home speakers**, not portable battery speakers:

| Sanitized model token / original product source | Variants / addresses / scans | Advertising clue |
|---|---:|---|
| [Stanmore II Bluetooth](https://www.marshall.com/tr/en/support/speakers/support-for-stanmore-ii-bluetooth) | **3 / 3 / 31** | Name exactly `Stanmore II`, UUID `FE8F`; `01/1,03/2,FF/2,09/11` |
| [Stanmore III](https://www.marshall.com/product/stanmore-iii) | **5 / 5 / 65** | Name contains `Stanmore III` (suffix hidden); two with UUID `AA00`, three with service-data `FE2C` |

Two Stanmore III broadcast layouts do **not** imply extra speaker models; `FE2C` is generic Fast Pair, and neither names nor UUIDs authenticate speakers or identify color/market variants.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
