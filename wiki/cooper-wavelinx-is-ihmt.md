---
qmd:
  metadata:
    title: "Cooper WaveLinx IS-IHMT sensor"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Cooper WaveLinx `IS-IHMT` sensor

## How to recognize it

- **Local name:** `IS-IHMT`
- **AD shape (ordered type/data length):** `01/1,09/7`
- **What it is:** Cooper WaveLinx industrial fixture-mounted occupancy/light sensor candidate.
- **Confidence and limits:** PIR/ambient-light candidate; PDF text not independently fetched. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Cooper WaveLinx industrial high-bay fixture-mounted occupancy/light sensor candidate](https://www.cooperlighting.com/global/brands/wavelinx/893030/lite-industrial-fixture-mount-high-low-bay-sensor-wik-wh-wim-wh) | Exact `IS-IHMT`; `01/1,09/7` only, no company or service UUID | **10 / 10 / 544** | [Original WaveLinx LITE manual](https://www.cooperlighting.com/api/assets/v1/file/CLS/content/db47d2dc91a94607a24ead40006edfae/WaveLinx-LITE-User-Manual.pdf) search index maps the name; PDF text was not independently fetched. PIR occupancy/ambient-light **candidate**, not temperature/humidity; exact order SKU and LITE/PRO state unknown. |
