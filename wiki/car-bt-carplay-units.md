---
qmd:
  metadata:
    title: "CAR-BT-* wireless CarPlay/Android Auto units"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `CAR-BT-*` wireless CarPlay/Android Auto units

## How to recognize it

- **Local name:** `CAR-BT-*`
- **AD shape (ordered type/data length):** `01/1,09/15,01/1`
- **What it is:** Generic wireless CarPlay/Android Auto multimedia unit candidate.
- **Confidence and limits:** Generic candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Generic wireless CarPlay/Android Auto multimedia unit `CAR-BT-*` candidate](https://ae01.alicdn.com/kf/S050166a648924dcba0740c047233e847c.pdf) | Literal `CAR-BT-*` (suffix hidden), ordered `01/1,09/15,01/1`; no company or service UUID | **8 / 8 / 26** | Third-party e-commerce-hosted product manual pp.6,8 instructs pairing a `CAR-BT-…` Bluetooth name, **not** a unique brand/model mapping or proof of an active CarPlay session. `local_name` length 14 versus AD name length 15 is unexplained; no repair assumed. Older 6 / 6 / 21 snapshot superseded. |
