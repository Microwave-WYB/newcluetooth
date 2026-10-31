---
qmd:
  metadata:
    title: "Wyze Wyze_Hub"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Wyze `Wyze_Hub`

## How to recognize it

- **Local name:** `Wyze_Hub`
- **Company field:** company field 2160
- **AD shape (ordered type/data length):** `01/1,FF/13,09/8`
- **What it is:** Wyze Sense Hub home-security hub candidate.
- **Confidence and limits:** Candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Wyze Sense Hub home-security hub candidate](https://www.wyze.com/products/wyze-hms-bundle) | Name exactly `Wyze_Hub`; `01/1,FF/13,09/8`; auxiliary company field 2160; no advertised service UUID | **8 / 8 / 79** | Wyze confirms hub BLE capability, but not this exact advertised name or hardware revision; its 915 MHz sensor link must **not** be mislabeled BLE. |
