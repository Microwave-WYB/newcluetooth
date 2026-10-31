---
qmd:
  metadata:
    title: "Switch-Pros SWCHPRO SP9100"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Switch-Pros `SWCHPRO` SP9100

## How to recognize it

- **Local name:** `SWCHPRO`
- **Service / service-data UUID:** `00035b03-58e6-07dd-021a-08123a000300`
- **AD shape (ordered type/data length):** `01/1,07/16,09/7`
- **What it is:** Switch-Pros SP9100 vehicle accessory panel candidate.
- **Confidence and limits:** Candidate; default name can change. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Switch-Pros SP9100 vehicle accessory switch/power panel candidate](https://www.switchpros.com/wp-content/uploads/SP9100-Rev-2.1-Oct-2024-Final.pdf) | Name exactly `SWCHPRO`, default SP9100 Bluetooth panel name in original manual §7.4; `01/1,07/16,09/7`, service UUID `00035b03-58e6-07dd-021a-08123a000300` | **6 / 6 / 297** | 12 V, eight programmable vehicle accessory circuits, **not** a building wall switch; default name can change and neither name nor UUID authenticates hardware/version. |
