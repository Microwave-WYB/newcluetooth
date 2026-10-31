---
qmd:
  metadata:
    title: "Ring Ring* security ecosystem (company field 369)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Ring `Ring*` security ecosystem (company field 369)

## How to recognize it

- **Local name:** `Ring*`
- **Company field:** company field 369
- **Service / service-data UUID:** `9760face-a234-4686-9e00-fcbbee000002`
- **What it is:** Ring connected-security ecosystem.
- **Confidence and limits:** Ecosystem candidate; chime/doorbell/camera not distinguished. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Ring connected-security ecosystem](https://en-uk.ring.com/products/smart-security-chime-3rd-gen) | `Ring*` (name 7 bytes) **plus** service UUID `9760face-a234-4686-9e00-fcbbee000002` and auxiliary Amazon company field 369; several ordered AD shapes | **36 / 36 / 972** | Bare `Ring*` alone gives 39 / 39 / 983; three rows excluded from the corroborated combination. Chime, doorbell and camera are distinct possible products; linked Chime is a BLE example, **not** a model identification. |
