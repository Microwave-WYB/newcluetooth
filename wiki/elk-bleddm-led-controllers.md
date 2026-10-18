---
qmd:
  metadata:
    title: "ELK-BLEDDM* generic BLE LED strip/light controllers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `ELK-BLEDDM*` generic BLE LED strip/light controllers

## How to recognize it

- **Local name:** `ELK-BLEDDM*`
- **AD shape (ordered type/data length):** `01/1,09/13`
- **What it is:** Generic BLE LED strip/light controller candidate named `ELK-BLEDDM*`.
- **Confidence and limits:** Multi-brand, white-label or compatible-app controller class; no hardware maker is established. No attribution to ELK security products. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Generic BLE LED strip/light controller `ELK-BLEDDM*` candidate](https://github.com/dave-code-ruiz/elkbledom/issues/92) | Name `ELK-BLEDDM*` (suffix hidden); 9 with `01/1,09/13`, 3 with repeated `09` fields; no advertised UUID/company field | **12 / 12 / 312** | Third-party hands-on report pairs the same-named light controller, but its GATT `FFF0/FFF3/FFF4` are **not** observed in these advertisements. Maker/model and RGB capabilities unknown; no attribution to ELK security products. |
