---
qmd:
  metadata:
    title: "BlazePod BlazePod pods"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# BlazePod `BlazePod` pods

## How to recognize it

- **Local name:** `BlazePod`
- **Company field:** company field 2519
- **Service / service-data UUID:** `50c928bd-4cb8-4c84-b745-0e58a0280cd6`
- **AD shape (ordered type/data length):** `01/1,07/16,09/8,FF/8`
- **What it is:** BlazePod LED reaction-training Pod family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [BlazePod LED reaction-training Pod family](https://www.blazepod.com/products/mini-kit) | Name `BlazePod`, service UUID `50c928bd-4cb8-4c84-b745-0e58a0280cd6`, auxiliary company field 2519; `01/1,07/16,09/8,FF/8` | **11 / 11 / 30** | [Independent hands-on protocol study](https://github.com/sasodoma/blazepod-hacking) links the UUID to pods; original Mini Kit page shows use case, **not** proof of package size, hardware generation or 11 purchased kits. |
