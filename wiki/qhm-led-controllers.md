---
qmd:
  metadata:
    title: "QHM-* multi-brand BLE LED lighting controllers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `QHM-*` multi-brand BLE LED lighting controllers

## How to recognize it

- **Local name:** `QHM-*`
- **AD shape (ordered type/data length):** `01/1,09/8`
- **What it is:** Multi-brand BLE LED lighting/control candidate named `QHM-*`.
- **Confidence and limits:** Multi-brand, white-label or compatible-app controller class; no hardware maker is established. Identified only through a third-party Home Assistant integration. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Multi-brand BLE LED lighting/control `QHM-*` candidate](https://www.home-assistant.io/integrations/led_ble/) | Literal `QHM-*` (suffix hidden, name 8 bytes); `01/1,09/8` only, no company/service UUID | **14 / 14 / 331** | Home Assistant **third-party** integration names `QHM` among supported multi-brand LED BLE devices; not a manufacturer, model, integrated fixture vs strip distinction, or medical sensor. Earlier 5 / 5 / 200 snapshot differs; exact-prefix recheck yields 14 / 14 / 331. |
