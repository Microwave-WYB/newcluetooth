---
qmd:
  metadata:
    title: "Lippert OneControl LCIRemote* (company field 1479)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Lippert OneControl `LCIRemote*` (company field 1479)

## How to recognize it

- **Local name:** `LCIRemote*`
- **Company field:** company field 1479
- **AD shape (ordered type/data length):** `01/1,FF/6,09/18,07/16`, `01/1,09/18`
- **What it is:** Lippert OneControl RV connectivity/control-module family.
- **Confidence and limits:** Family; name-only group is weaker. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Lippert OneControl RV-side connectivity/control-module family](https://www.lippert.com/brands/onecontrol/rv) | `LCIRemote*` (serial suffix hidden), **13 / 13 / 591** with Lippert-assigned company field 1479 and withheld shared 128-bit UUID (`01/1,FF/6,09/18,07/16`); **10 / 10 / 19** name-only (`01/1,09/18`) | **23 / 23 / 610** | [RV manufacturer's original manual](https://www.highlandridgerv.com/uploads/rvs/manuals/1-2021-Highland-Ridge-TT-OM.pdf) names `LCIRemote*` for OneControl Bluetooth pairing. Name-only group is weaker; **not** a confirmed hand-held remote, RV count, feature set, model or generation. Earlier 5 / 5 / 8 snapshot superseded. |
