---
qmd:
  metadata:
    title: "Right Weigh RWLS-* load scales"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Right Weigh `RWLS-*` load scales

## How to recognize it

- **Local name:** `RWLS-*`
- **Service / service-data UUID:** `1800`, `180A`
- **AD shape (ordered type/data length):** `01/1,03/4,07/16,09/22`
- **What it is:** Right Weigh Bluetooth on-vehicle load/axle-weight scale candidate.
- **Confidence and limits:** Candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Right Weigh Bluetooth on-vehicle load/axle-weight scale candidate](https://rwls.com/wp-content/uploads/2023/12/pp-003-0034-rev-e-201-ebt-04b-12-pin-1.pdf) | `RWLS-*` (device suffix hidden, name 22 bytes); generic `1800/180A`, plus withheld unverified 128-bit UUID; `01/1,03/4,07/16,09/22` | **6 / 6 / 81** | Manufacturer manual documents Bluetooth truck/trailer suspension-pressure-calibrated load scales, **not** that these are model 201-EBT-04(B) or have a specific sensor count. Older snapshot 5 / 5 / 77 superseded. |
