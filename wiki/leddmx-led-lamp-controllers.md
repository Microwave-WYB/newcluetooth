---
qmd:
  metadata:
    title: "LEDDMX-* LED LAMP-compatible colour-light controllers"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `LEDDMX-*` LED LAMP-compatible colour-light controllers

## How to recognize it

- **Local name:** `LEDDMX-00-*`, `LEDDMX-03-*`
- **Service / service-data UUID:** `FFB0`
- **What it is:** LED LAMP-compatible colour-light controller candidate identified by the `LEDDMX-00-*` / `LEDDMX-03-*` name prefix.
- **Confidence and limits:** Multi-brand, white-label or compatible-app controller class; no hardware maker is established. The `DMX` name does not establish professional DMX512 support. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [LED LAMP-compatible color-light controller candidate](https://apps.apple.com/tm/app/led-lamp/id1449109039) | `LEDDMX-00-*` (10 / 10 / 1,453) or `LEDDMX-03-*` (3 / 3 / 11); some advertise `FFB0`; [independent hands-on name clue](https://github.com/user154lt/LEDDMX-00) | **13 / 13 / 1,464** | App developer is **not** proven the hardware maker; `DMX` name does not establish professional DMX512 support. Hands-on GATT `FFE0` and observed advertising `FFB0` are distinct, with no asserted equivalence. |
