---
qmd:
  metadata:
    title: "Schlage Schlage* locks (company field 315)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Schlage `Schlage*` locks (company field 315)

## How to recognize it

- **Local name:** `Schlage*`
- **Company field:** company field 315
- **AD shape (ordered type/data length):** `01/1,FF/19,09/15`
- **What it is:** Schlage mobile-enabled smart-lock/access family.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Schlage mobile-enabled locks](https://commercial.schlage.com/en/products/electronic-locks/schlage-control-mobile-enabled-smart-locks.html) | `Schlage*` (name 15 bytes); `01/1, FF/19, 09/15`; company field 315 auxiliary | **10 / 10 / 1,212** | Smart-lock/access family; linked Control model is an example, **not** proven from these advertisements. |
