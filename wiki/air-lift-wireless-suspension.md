---
qmd:
  metadata:
    title: "Air Lift wirelessone-* / wirelessair-* air suspension (FA00)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Air Lift `wirelessone-*` / `wirelessair-*` air suspension (`FA00`)

## How to recognize it

- **Local name:** `wirelessone-*`, `wirelessair-*`
- **Company field:** company field 305
- **Service / service-data UUID:** `FA00`
- **AD shape (ordered type/data length):** `01/1,16/4,09/18`, `01/1,09/18,09/18,FF/4`
- **What it is:** Air Lift WirelessOne and WirelessAir air-suspension control manifolds.
- **Confidence and limits:** Product candidates from original manuals. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Air Lift WirelessOne vehicle air-suspension control manifold](https://content.airliftcompany.com/manuals/AD-946.pdf) | Case-insensitive `wirelessone-*` (suffix hidden); `01/1,16/4,09/18`, service-data UUID `FA00`; no manufacturer field | **12 / 12 / 70** | Original user guide explicitly says App discovers a manifold with WirelessOne in its name. `FA00` meaning, kit number and compressor generation remain unverified; addresses are not system counts. |
| [Air Lift WirelessAir dual-channel air-suspension manifold](https://digital.airliftcompany.com/m/4d4bb1a6c972f452/original/AD-963.pdf) | Case-insensitive `wirelessair-*` (serial suffix hidden); `01/1,09/18,09/18,FF/4`, auxiliary company field 305, no advertised service UUID | **5 / 5 / 188** | Original manual names WirelessAir manifold and independent left/right pressure control. Distinct from single-channel WirelessOne, but kit SKU/vehicle installation unknown. |
