---
qmd:
  metadata:
    title: "Hoopo HOOPO* hoopoSense asset tracker"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Hoopo `HOOPO*` hoopoSense asset tracker

## How to recognize it

- **Local name:** `HOOPO*`
- **Company field:** company field 0xFFFF
- **AD shape (ordered type/data length):** `01/1,FF/2,16/4 or 6,09/14`
- **What it is:** Hoopo hoopoSense asset-tracker family.
- **Confidence and limits:** Family only. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Hoopo hoopoSense asset tracker](https://www.hoopo.com/hooposense) | `HOOPO*` (name 14 bytes); `01/1, FF/2, 16/4 or 6, 09/14` | **12 / 12 / 26** | Family only; `0xFFFF` company field is not reliable vendor proof. Unordered shapes collide. |
