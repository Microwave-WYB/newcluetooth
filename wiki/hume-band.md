---
qmd:
  metadata:
    title: "Hume hume band* wearable"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Hume `hume band*` wearable

## How to recognize it

- **Local name:** `hume band*`
- **Service / service-data UUID:** `180D`
- **AD shape (ordered type/data length):** `FF/28,09/14,01/1,03/2`
- **What it is:** Hume Health Hume Band wellness wearable.
- **Confidence and limits:** Product candidate; not a medical device. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Hume Health Hume Band wellness wearable](https://humehealth.com/pages/humeband-quickstart) | Case-insensitive `hume band*` (device suffix hidden), generic Heart Rate UUID `180D`; `FF/28,09/14,01/1,03/2` | **11 / 8 / 43** | Official quickstart documents BLE pairing and explicitly says **not a medical device**. Not a Hume body-fat scale; no hardware generation or physical-unit count established. |
