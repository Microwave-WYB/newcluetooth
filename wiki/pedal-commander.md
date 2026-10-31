---
qmd:
  metadata:
    title: "Pedal Commander Pedal Commander and pedal*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Pedal Commander `Pedal Commander` and `pedal*`

## How to recognize it

- **Local name:** `Pedal Commander`, `pedal*`
- **Company field:** company field 89
- **AD shape (ordered type/data length):** `01/1,09/15,0A/1`, `19/2,01/1,FF/5,09/13,07/16`
- **What it is:** Pedal Commander throttle-response controller candidate; other `pedal*` names unassigned.
- **Confidence and limits:** Candidate for the exact name only. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Pedal Commander Bluetooth throttle-response controller candidate](https://pedalcommander.com/pages/app) | Exact public name `Pedal Commander`; `01/1,09/15,0A/1`, no company or service UUID | **1 / 1 / 2** | Manufacturer documents App-controlled vehicle throttle-response hardware, **not** this advertiser's authenticity or vehicle fit. Other `pedal*` names below do **not** belong to this product on present evidence. |

## Related observations

Four other case-insensitive `pedal*` advertisements (**4 exact variants / 4 addresses / 8 scans**, name 13 bytes, suffix hidden) use Nordic Semiconductor-assigned company field 89 and generic Nordic UART Service with ordered `19/2,01/1,FF/5,09/13,07/16`. Neither signal binds them to Pedal Commander; **manufacturer of the end product and its use remain unknown**. Full `pedal*` name cohort is 5 / 5 / 10; strict lowercase match finds none.
