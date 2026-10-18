---
qmd:
  metadata:
    title: "GoPro BLE protocol (FEA6)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# GoPro BLE protocol (`FEA6`)

## How to recognize it

- **Local name:** `GoPro*`
- **Service / service-data UUID:** `FEA6`
- **AD shape (ordered type/data length):** `01/1,02/2,FF/14,09/10,16/10`
- **What it is:** GoPro action-camera family via the documented Open GoPro BLE service.
- **Confidence and limits:** Camera-family attribution supported by the official protocol; model IDs are from a third-party mapping. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [GoPro action-camera BLE protocol](https://gopro.github.io/OpenGoPro/docs/ble/protocol/ble_setup/) | `GoPro*`; service and service-data UUID `FEA6`; `01/1, 02/2, FF/14, 09/10, 16/10`; model-ID field 62 (15 variants / 9 addresses / 1,418 scans), 65 (12 / 12 / 115) | **27 / 21 / 1,533** | Official protocol supports camera-family attribution. [Third-party model mapping](https://github.com/hypoxic/GoPro-Research) suggests ID 62 = HERO12 Black and 65 = HERO13 Black; **not** an official model confirmation. |
