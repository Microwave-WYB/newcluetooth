---
qmd:
  metadata:
    title: "Procon ARROWQ vehicle telematics (company field 3027)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Procon `ARROWQ` vehicle telematics (company field 3027)

## How to recognize it

- **Local name:** `ARROWQ`
- **Company field:** company field 3027
- **AD shape (ordered type/data length):** `01/1,0A/1,FF/10,09/6`
- **What it is:** Procon-associated vehicle-telematics candidate.
- **Confidence and limits:** Candidate; company field is not authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Procon Analytics-associated vehicle-telematics `ARROWQ` **candidate**](https://www.proconanalytics.com/portfolio-item/procon-automotive-2/) | Case-insensitive exact `ARROWQ` (name 6 bytes), Procon-assigned company field 3027; `01/1,0A/1,FF/10,09/6`, no advertised service UUID | **7 / 7 / 105** | Original Procon portfolio supports vehicle GPS/asset telematics category, **not** an exact `ARROWQ` hardware mapping; company field is not authentication. No model, installed car or Phillips Arrow identification established. |
