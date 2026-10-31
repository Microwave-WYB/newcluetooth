---
qmd:
  metadata:
    title: "Tile Tile item finder (FEED)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Tile `Tile` item finder (`FEED`)

## How to recognize it

- **Local name:** `Tile`
- **Service / service-data UUID:** `FEED`
- **AD shape (ordered type/data length):** `01/1,03/2,16/12,09/4`
- **What it is:** Tile Bluetooth item-finder family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Tile Bluetooth item-finder family](https://www.life360.com/tile-trackers) | Name exactly `Tile` plus service and service-data UUID `FEED`; generally `01/1, 03/2, 16/12, 09/4` | **29 / 20 / 102** | Multiple exact advertisements per address; Mate, Pro, Slim, Sticker and generations cannot be distinguished. |
