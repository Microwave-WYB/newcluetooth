---
qmd:
  metadata:
    title: "coolledux programmable LED display (FFF0)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `coolledux` programmable LED display (`FFF0`)

## How to recognize it

- **Local name:** `coolledux`
- **Service / service-data UUID:** `FFF0`
- **AD shape (ordered type/data length):** `01/1,03/2,FF/13,09/9`
- **What it is:** CoolLEDUX programmable RGB LED pixel-message display candidate.
- **Confidence and limits:** Candidate; do not merge with `coolledx`. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [CoolLEDUX programmable RGB LED pixel-message display candidate](https://github.com/CharlesLennon/coolledux-ble) | Name exactly `coolledux`, generic UUID `FFF0`; `01/1,03/2,FF/13,09/9`, varying company field | **10 / 10 / 449** | Independent hands-on controller reverse engineering demonstrates text/graphics/animation on a physical matrix display. **Not** a generic dimmable strip, LEDnetWF, or automatically the older CoolLEDX protocol; manufacturer, panel size and retail SKU unknown. |
