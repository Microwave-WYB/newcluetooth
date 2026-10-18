---
qmd:
  metadata:
    title: "WHOOP WHOOP* wearables"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# WHOOP `WHOOP*` wearables

## How to recognize it

- **Local name:** `WHOOP*`
- **AD shape (ordered type/data length):** `01/1,03/2,09/*`, `01/1,03/2,09/*,07/16`
- **What it is:** WHOOP health/fitness wearable family.
- **Confidence and limits:** Family candidate; shape not exclusive. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [WHOOP health/fitness wearable family](https://www.whoop.com/us/en/press-center/whoop-unveils-5.0-MG/) | Case-insensitive `WHOOP*`; no manufacturer field required; chiefly `01/1, 03/2, 09/*`, sometimes followed by `07/16` | **92 / 90 / 5,351** | One name includes the `WHOOP 5.0` model token; do **not** extend that generation to the other variants. Counts rechecked after an earlier 89 / 87 / 5,317 snapshot; shape is not exclusive. |
