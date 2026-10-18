---
qmd:
  metadata:
    title: "SRAM SRAM* AXS (FE51)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# SRAM `SRAM*` AXS (`FE51`)

## How to recognize it

- **Local name:** `SRAM*`
- **Company field:** company field 2355
- **Service / service-data UUID:** `FE51`
- **What it is:** SRAM AXS bicycle-electronics family.
- **Confidence and limits:** Family; component classes not distinguished. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [SRAM AXS bicycle-electronics family](https://www.sram.com/en/sram/road/campaigns/axs-app) | `SRAM*` plus assigned service/service-data UUID `FE51`; five have auxiliary SRAM company field 2355, three do not | **8 / 7 / 18** | Electronic shifting, power-meter and other component classes cannot be distinguished. Two AD layouts are **not** two proven models. |
