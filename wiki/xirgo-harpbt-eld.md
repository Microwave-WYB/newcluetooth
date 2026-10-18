---
qmd:
  metadata:
    title: "Xirgo/Harp HarpBT* ELD telematics"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Xirgo/Harp `HarpBT*` ELD telematics

## How to recognize it

- **Local name:** `HarpBT*`
- **Service / service-data UUID:** `1b19b844-038f-11e5-8418-1697f925ec7b`, `a59611ba-78b7-4fd2-96fb-9b0f66d2311e`
- **What it is:** Xirgo/Harp vehicle ELD and telematics family.
- **Confidence and limits:** Family; not all records proven TT6000 or Titan-branded. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Xirgo/Harp vehicle ELD and telematics family](https://titangps.com/wp-content/uploads/2022/02/FINAL_Titan-ELD2.0-Driver-manual-RecE-Jan-2022.pdf) | `HarpBT*` (serial-bearing suffix hidden); [Xirgo Engine/HOS service UUIDs](https://xirgo.gitlab.io/telematics/reanimate/antora/XT6300/HB1/reAnimate/Configurations/97_ELD_User_Services.html) `1b19b844-038f-11e5-8418-1697f925ec7b` (97 / 97 / 2,022) or `a59611ba-78b7-4fd2-96fb-9b0f66d2311e` (16 / 16 / 84) | **113 / 113 / 2,106** | Titan's manual documents the exact `HarpBT{serial}` naming scheme and a TT6000 example; **not all records are proven to be TT6000 or Titan-branded**. |
