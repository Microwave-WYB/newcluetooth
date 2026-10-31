---
qmd:
  metadata:
    title: "Renogy-compatible BT-TH-* (FFD0)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Renogy-compatible `BT-TH-*` (`FFD0`)

## How to recognize it

- **Local name:** `BT-TH-*`
- **Service / service-data UUID:** `FFD0`
- **AD shape (ordered type/data length):** `01/1,02/2,FF/6,0A/1,09/18`
- **What it is:** Renogy-compatible BLE solar-energy monitoring candidate.
- **Confidence and limits:** Candidate; `BT-TH` does not establish a temperature/humidity sensor. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Renogy-compatible BLE solar-energy monitoring **candidate**](https://www.renogy.com/collections/charge-controllers/products/bt-1-bluetooth-module-new-version) | `BT-TH-*` (suffix hidden), name 18 bytes and service UUID `FFD0`; `01/1,02/2,FF/6,0A/1,09/18` | **18 / 18 / 342** | Renogy BT-1 product monitors charge controllers, but official source does not identify these ads as BT-1/BT-2 or a downstream model. `BT-TH` does **not** establish a temperature/humidity sensor. Two shorter no-UUID variants are excluded. |

## Related observations

Two shorter `BT-TH-*` observations (name 14 bytes, **2 variants / 2 addresses / 5 scans**) have no `FFD0` UUID. They are **not** included in the Renogy-compatible row and remain unassigned; the full case-insensitive name cohort is 20 / 20 / 347 versus an earlier 12 / 12 / 206 snapshot.
