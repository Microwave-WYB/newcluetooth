---
qmd:
  metadata:
    title: "Lime lime-* shared micromobility"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Lime `lime-*` shared micromobility

## How to recognize it

- **Local name:** `lime-*`
- **What it is:** Lime shared bike/scooter fleet candidate.
- **Confidence and limits:** Fleet candidate; no common company field or UUID. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Lime shared micromobility vehicles](https://www.li.me/vehicles/electric-bike/) | Case-insensitive `lime-*` (name 17 bytes); no common company field/UUID; AD order varies, including malformed-looking `00` and duplicate-field cases | **26 / 26 / 235** | Name supports a shared bike/scooter fleet candidate, **not** a confirmed vehicle type or model. Do not infer an exclusive protocol from unusual parsed AD fields. |
