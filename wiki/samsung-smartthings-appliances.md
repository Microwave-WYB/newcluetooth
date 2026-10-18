---
qmd:
  metadata:
    title: "Samsung SmartThings appliances: washer, dryer, refrigerato (company field 117)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Samsung SmartThings appliances: `washer`, `dryer`, `refrigerato` (company field 117)

## How to recognize it

- **Local name:** `washer`, `dryer`, `refrigerato`
- **Company field:** company field 117
- **AD shape (ordered type/data length):** `01/1,FF/26,FF/10,09/6`, `01/1,FF/26,FF/10,09/5`, `01/1,FF/26,FF/10,09/11`
- **What it is:** Samsung SmartThings-connected washer, dryer and refrigerator family leads sharing one broadcast structure.
- **Confidence and limits:** Family leads, not SKUs or evidence of active SmartThings use. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Samsung SmartThings-connected washer family](https://www.samsung.com/us/support/answer/ANS10001025/) | Name exactly `washer`; auxiliary company field 117; `01/1, FF/26, FF/10, 09/6` | **21 / 21 / 223** | Generic category name and company field support a family lead, **not** a specific SKU or evidence of active SmartThings use. |
| [Samsung SmartThings-connected dryer family](https://www.samsung.com/us/support/answer/ANS10001030/) | Name exactly `dryer`; auxiliary company field 117; `01/1, FF/26, FF/10, 09/5` | **27 / 27 / 520** | Similar broadcast structure does **not** show any washer/dryer pair is co-installed or connected. |
| [Samsung SmartThings refrigerator family candidate](https://www.samsung.com/us/support/answer/ANS10001624/) | Name exactly `refrigerato` plus auxiliary Samsung company field 117; `01/1,FF/26,FF/10,09/11`; no advertised service UUID | **10 / 10 / 579** | Official refrigerator discovery/Wi-Fi setup does **not** document this BLE name or prove telemetry; model, Family Hub status and actual appliance identity unresolved. |
