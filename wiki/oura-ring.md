---
qmd:
  metadata:
    title: "Oura Oura* ring"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Oura `Oura*` ring

## How to recognize it

- **Local name:** `Oura*`
- **Company field:** company field 690
- **Service / service-data UUID:** `98ed0001-a541-11e4-b6a0-0002a5d5c51b`
- **AD shape (ordered type/data length):** `01/1,06/16,FF/6,09/11 or 14`
- **What it is:** Oura smart ring family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Oura Ring smart-wearable family](https://ouraring.com/how-it-works) | `Oura*`; UUID `98ed0001-a541-11e4-b6a0-0002a5d5c51b`; `01/1, 06/16, FF/6, 09/11 or 14`; company 690 auxiliary | **35 / 35 / 580** | Sleep/activity/recovery wearable; two name lengths do not prove different generations. |
