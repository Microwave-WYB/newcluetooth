---
qmd:
  metadata:
    title: "Yamaha YCCU_* motorcycle connectivity unit"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Yamaha `YCCU_*` motorcycle connectivity unit

## How to recognize it

- **Local name:** `YCCU_*`
- **AD shape (ordered type/data length):** `01/1,09/19`
- **What it is:** Yamaha motorcycle/scooter smartphone-connectivity-unit candidate.
- **Confidence and limits:** Candidate from third-party dealer guides. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Yamaha motorcycle/scooter smartphone-connectivity-unit candidate](https://www.gaertners-motorradshop.de/motorr%C3%A4der/neufahrzeuge/smartphone-konnektivit%C3%A4t/) | `YCCU_*` (literal underscore, suffix hidden), `01/1,09/19` only, no company/service UUID | **6 / 6 / 87** | **Third-party motorcycle dealer** pairing guides directly mention `YCCU_` for Yamaha MyRide(-Link); not an original manufacturer broadcast specification. No bike model/year, module SKU or enabled feature set inferred. |
