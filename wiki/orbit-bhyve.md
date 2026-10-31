---
qmd:
  metadata:
    title: "Orbit bhyve_* irrigation (FE32)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Orbit `bhyve_*` irrigation (`FE32`)

## How to recognize it

- **Local name:** `bhyve_*`
- **Company field:** company field 1151
- **Service / service-data UUID:** `FE32`
- **AD shape (ordered type/data length):** `01/1,03/2,09/12,FF/4`
- **What it is:** Orbit B-hyve Bluetooth irrigation-control family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Orbit B-hyve Bluetooth irrigation-control family](https://www.orbitonline.com/products/orbit-21205) | Literal `bhyve_*` (suffix hidden); auxiliary company field 1151; six have service UUID `FE32` and `01/1,03/2,09/12,FF/4`, one has no advertised service UUID | **7 / 7 / 9** | Official 21205 timer demonstrates Bluetooth irrigation setup, **not** proof these broadcasts are that SKU, generation or a single-outlet faucet timer. |
