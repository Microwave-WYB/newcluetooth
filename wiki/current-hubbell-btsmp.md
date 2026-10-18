---
qmd:
  metadata:
    title: "Current/Hubbell BTSMP* luminaire sensor modules"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Current/Hubbell `BTSMP*` luminaire sensor modules

## How to recognize it

- **Local name:** `BTSMP*`
- **AD shape (ordered type/data length):** `01/1,06/16,FF/4,09/24`
- **What it is:** Current/Hubbell BTSMP Bluetooth luminaire sensor-module family candidate.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Current/Hubbell BTSMP Bluetooth luminaire sensor-module family candidate](https://cdn.currentlighting.com/site/installationmanuals/1902B_BTSMP_Install.pdf) | `BTSMP*` (suffix hidden), ordered `01/1,06/16,FF/4,09/24`; unverified 128-bit UUID withheld | **5 / 5 / 32** | Original installation guide lists PIR occupancy/daylight lighting-control modules BTSMP-HMO/LMO/LMI/OMNI, **none** identifiable from this name. No height/indoor-outdoor submodel split or UUID-to-product proof. |
