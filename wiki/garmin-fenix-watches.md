---
qmd:
  metadata:
    title: "Garmin fenix* multisport watches"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Garmin `fenix*` multisport watches

## How to recognize it

- **Local name:** `fenix*`
- **Service / service-data UUID:** `6a4e3e10-667b-11e3-949a-0800200c9a66`, `180D`
- **What it is:** Garmin fēnix 3 HR / 5 / 5X / 6X Pro Solar name tokens.
- **Confidence and limits:** Model-name candidates; not device authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Garmin fēnix multisport GPS watches

The `fenix*` first-party cohort is **9 exact `(addr,raw)` variants / 9 addresses / 614 scans**. Match complete visible Garmin model tokens with boundaries (`5X` must not collapse into `5`):

| Sanitized name model token / original product source | Variants / addresses / scans |
|---|---:|
| [`fenix 5` → Garmin fēnix 5](https://www.garmin.com/en-US/blog/outdoor/fenix-5-family-size-comparison/) | **4 / 4 / 603** |
| [`fenix 5X` → Garmin fēnix 5X](https://www.garmin.com/en-US/blog/outdoor/fenix-5-family-size-comparison/) | **3 / 3 / 8** |
| [`fenix 3 HR` → Garmin fēnix 3 HR](https://www.garmin.com/en-US/blog/outdoor/introducing-the-expanded-fenix-3-lineup/) | **1 / 1 / 1** |
| [`fenix 6X Pro Solar` → Garmin fēnix 6X Pro Solar](https://www.garmin.com/en-US/p/641375/pn/010-02157-23/) | **1 / 1 / 2** |

Three earlier generations advertise the same 128-bit service UUID `6a4e3e10-667b-11e3-949a-0800200c9a66`; the 6X entry advertises generic Heart Rate service `180D`. Name tokens support candidates, not device authentication, precise hardware revisions or the number of physical watches.

A direct whole-prefix recheck confirmed **all 9 / 9 / 614** in the four mutually exclusive rows above. The earlier generic `fenix 3` assignment was a **truncated classification of the same 1 / 1 / 1 `fenix 3 HR` row**; generic `fenix 3` alone has **zero** matches under full-token boundaries. The `HR` name is not evidence an actual heart-rate measurement occurred.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
