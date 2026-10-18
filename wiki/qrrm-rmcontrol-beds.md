---
qmd:
  metadata:
    title: "QRRM* RMControl adjustable-bed controllers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `QRRM*` RMControl adjustable-bed controllers

## How to recognize it

- **Local name:** `QRRM*`
- **AD shape (ordered type/data length):** `08/10`, `01/1,06/16`
- **What it is:** RMControl-compatible adjustable-bed controller family candidate.
- **Confidence and limits:** Candidate from third-party manual. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [RMControl-compatible adjustable-bed controller `QRRM*` family candidate](https://linqcdn.avbportal.com/documents/96dab3c5-dc70-4a58-8c44-50c35096288d.pdf) | Literal Latin `QRRM*` (`M` U+004D, **not** Cyrillic `М`); shortened-local-name `08/10`, `01/1,06/16`; 128-bit UUID withheld, no company field or short service UUID | **5 / 5 / 8** | **Third-party dealer-hosted adjustable-bed manual**, pp. 6–7, directs RMControl pairing with `QRRM` prefix and describes head/foot elevation and massage. Name is shortened; no manufacturer, bed model, Richmat attribution, count of physical beds, medical-grade status or hospital setting established. |
