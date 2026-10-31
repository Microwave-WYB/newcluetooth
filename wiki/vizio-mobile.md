---
qmd:
  metadata:
    title: "VIZIO VIZIO Mobile App name"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# VIZIO `VIZIO Mobile App` name

## How to recognize it

- **Local name:** `VIZIO Mobile App`
- **Company field:** company field 88
- **Service / service-data UUID:** `ea1979cf-5313-4152-b056-37619c1da100`
- **AD shape (ordered type/data length):** `01/1,09/16,07/16,FF/2`
- **What it is:** VIZIO mobile control/pairing ecosystem.
- **Confidence and limits:** Ecosystem candidate; emitting device and role unknown. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [VIZIO Mobile control/pairing ecosystem](https://www.vizio.com/en/mobile/control) | Name exactly `VIZIO Mobile App`; UUID `ea1979cf-5313-4152-b056-37619c1da100`; `01/1, 09/16, 07/16, FF/2`; company field 88 auxiliary | **13 / 13 / 5,129** | [VIZIO's original soundbar manual, pp. 13–16](https://www.vizio.com/content/dam/vizio/2025/qsg/SV510M-0806_UM_v2.pdf) has the phone app discover **“VIZIO 5.1 Soundbar”**, but does **not** assign this different advertised name/UUID to the phone, soundbar or TV. Emitting device and role remain unknown; scans are not control events or a count of TV/phone/soundbar units. |
