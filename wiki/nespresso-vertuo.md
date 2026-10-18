---
qmd:
  metadata:
    title: "Nespresso Vertuo_DV* / Vertuo_CV*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Nespresso `Vertuo_DV*` / `Vertuo_CV*`

## How to recognize it

- **Local name:** `Vertuo_DV*`, `Vertuo_CV*`
- **Service / service-data UUID:** `06aa1910-f22a-11e3-9daa-0002a5d5c51b`
- **AD shape (ordered type/data length):** `01/1,06/16,FF/8,0A/1,09/23`
- **What it is:** Nespresso Vertuo connected coffee-machine family names.
- **Confidence and limits:** Family candidates; model unconfirmed. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Nespresso Vertuo connected coffee-machine family](https://www.nespresso.com/us/en/order/machines/vertuo/vertuo-next-light-grey) | Literal `Vertuo_DV*` (underscore is not a wildcard); UUID `06aa1910-f22a-11e3-9daa-0002a5d5c51b`; `01/1, 06/16, FF/8, 0A/1, 09/23` | **16 / 16 / 378** | Vertuo Next is a Bluetooth/Wi-Fi product example; UUID also occurs on other Nespresso machines, so exact model remains unconfirmed. |
| [Nespresso Vertuo `CV` name-family candidate](https://www.nespresso.com/shared_res/manuals/vertuo-next/Nespresso-Vertuo-Next_User-Manual_Cluster-3.pdf) | Literal `Vertuo_CV*` (device suffix hidden), same UUID `06aa1910-f22a-11e3-9daa-0002a5d5c51b` and `01/1,06/16,FF/8,0A/1,09/23` as `Vertuo_DV*` | **7 / 7 / 161** | The official manual lists GCV1/GDV1 machine codes but **does not link them to these BLE names**; CV and DV are distinct observed names, not established different retail models or SKUs. |
