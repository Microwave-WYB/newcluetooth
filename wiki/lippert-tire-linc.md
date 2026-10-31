---
qmd:
  metadata:
    title: "Lippert TireLinc TPMS repeater"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Lippert `TireLinc` TPMS repeater

## How to recognize it

- **Local name:** `TireLinc`
- **Service / service-data UUID:** `00000000-00b7-4807-beee-e0b0879cf3dd`
- **What it is:** Lippert Tire Linc repeater/gateway family.
- **Confidence and limits:** Family; repeater, not an in-tire sensor. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Lippert Tire Linc TPMS repeater/gateway family](https://lci-support-doc.s3.amazonaws.com/manuals/electronics/ccd-0004221.pdf) | Name exactly `TireLinc` as specified by original pairing manual p.5; 9 variants advertise UUID `00000000-00b7-4807-beee-e0b0879cf3dd`, one does not | **10 / 10 / 63** | Original instructions identify the **repeater** pairing name, not an in-tire pressure sensor. No kit part number, hardware generation or actual vehicle established; UUID not mandatory. |
