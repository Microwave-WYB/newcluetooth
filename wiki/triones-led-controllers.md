---
qmd:
  metadata:
    title: "Triones* Bluetooth colour-light controllers (FFD0, FFD5)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `Triones*` Bluetooth colour-light controllers (`FFD0`, `FFD5`)

## How to recognize it

- **Local name:** `Triones*`
- **Service / service-data UUID:** `FFD0`, `FFD5`
- **AD shape (ordered type/data length):** `01/1,02/4,FF/8,09/18`, `01/1,09/20`
- **What it is:** Triones-compatible Bluetooth colour-light control family.
- **Confidence and limits:** Multi-brand, white-label or compatible-app controller class; no hardware maker is established. Neither AD form identifies a fixture, controller or retail model. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Triones-compatible Bluetooth color-light control family](https://play.google.com/store/apps/details?hl=en_US&id=com.xiaoyu.gtriones) | Case-insensitive `Triones*` (device suffix hidden), **5 / 5 / 26** with `01/1,02/4,FF/8,09/18` and UUIDs `FFD0/FFD5`; **6 / 6 / 43** name-only `01/1,09/20` | **11 / 11 / 69** | App developer's listing names `Triones` as a pairing prefix for Bluetooth lights. Compatible devices may be multi-brand; neither AD form identifies fixture vs controller, bulb vs strip, retail model or two distinct product lines. Earlier 7 / 7 / 46 snapshot superseded. |
