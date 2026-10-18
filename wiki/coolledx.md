---
qmd:
  metadata:
    title: "coolledx programmable LED display (FFF0)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `coolledx` programmable LED display (`FFF0`)

## How to recognize it

- **Local name:** `coolledx`
- **Service / service-data UUID:** `FFF0`
- **AD shape (ordered type/data length):** `01/1,02/2,FF/13,12/4,0A/1,12/4,09/8`
- **What it is:** CoolLEDX / CoolLED1248-compatible programmable LED display candidate.
- **Confidence and limits:** Candidate; do not merge with `coolledux`. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [CoolLEDX / CoolLED1248-compatible programmable LED pixel-message display candidate](https://pypi.org/project/coolledx/) | Name exactly `coolledx`, generic UUID `FFF0`; `01/1,02/2,FF/13,12/4,0A/1,12/4,09/8` preserving repeated `12`; company field varies | **8 / 8 / 8,435** | Independent hardware driver and [app developer listing](https://apps.apple.com/us/app/coolled1248/id1435796557) support animated text/image display. Not a plain LED strip, manufacturer/model/size unknown; **do not merge** with distinct CoolLEDUX protocol on name similarity. |
