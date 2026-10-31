---
qmd:
  metadata:
    title: "RoadPro POWERDRIVER-L* inverters"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# RoadPro `POWERDRIVER-L*` inverters

## How to recognize it

- **Local name:** `POWERDRIVER-L*`
- **Company field:** company field 76
- **AD shape (ordered type/data length):** `09/17`, `01/1,0A/1,FF/9,09/17`
- **What it is:** PowerDrive Bluetooth vehicle-inverter family candidate.
- **Confidence and limits:** Family candidate; company field 76 on four variants is Apple, not PowerDrive. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [PowerDrive Bluetooth vehicle-inverter family candidate](https://roadprobrands.com/files/files/roadpro/resource%20library/101/2015_powerdrive2_sellsheet_v7.pdf?download=false) | `POWERDRIVER-L*` (name 17 bytes); chiefly `09/17`, sometimes `01/1, 0A/1, FF/9, 09/17` | **30 / 30 / 225** | RoadPro describes vehicle DC-to-AC inverters; [mirrored manual naming clue](https://manuals.plus/m/3e353e809a1e1964f1ff60604fa7a2a15f619cc81fcd9930ec19df148a540b75) is not original manufacturer distribution. PD1000/1500/2000/3000 SKU unproven; company field 76 on four variants is **Apple, not PowerDrive**. Earlier snapshot was 21 / 21 / 126. |
