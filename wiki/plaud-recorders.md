---
qmd:
  metadata:
    title: "Plaud Plaud Note* / Plaud NotePin* AI recorders"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Plaud `Plaud Note*` / `Plaud NotePin*` AI recorders

## How to recognize it

- **Local name:** `Plaud Note*`, `Plaud NotePin*`
- **Company field:** company field 93, company field 89
- **AD shape (ordered type/data length):** `FF/29,09/14`, `01/1,09/10,FF/29`, `01/1,FF/26,09/13 or 15`
- **What it is:** Plaud Note card recorder and NotePin wearable.
- **Confidence and limits:** Family candidates; manufacturer ID is not a required filter. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Plaud Note AI recorder](https://support.plaud.ai/hc/en-us/articles/54905871433241-What-is-the-difference-between-Plaud-Note-and-Plaud-NotePin) | `Plaud Note*` excluding NotePin; chiefly `FF/29, 09/14`, sometimes `01/1, 09/10, FF/29` | **11 / 11 / 35** | Card-style recorder family; the smaller variant has a different company field (89 versus 93), so manufacturer ID is not a required filter. No generation inferred. |
| [Plaud NotePin wearable AI recorder](https://www.plaud.ai/products/plaud-notepin) | `Plaud NotePin*`; `01/1, FF/26, 09/13 or 15` | **11 / 11 / 76** | Wearable recording family, not a confirmed hardware revision. Combined `Plaud*` is 22 / 22 / 111. |
