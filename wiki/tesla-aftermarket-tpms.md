---
qmd:
  metadata:
    title: "tsTPMS aftermarket Tesla-compatible TPMS (company field 555)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `tsTPMS` aftermarket Tesla-compatible TPMS (company field 555)

## How to recognize it

- **Local name:** `tsTPMS`
- **Company field:** company field 555
- **Service / service-data UUID:** `1122`
- **AD shape (ordered type/data length):** `01,FF,02,09`
- **What it is:** Tesla-compatible aftermarket TPMS family candidate.
- **Confidence and limits:** Aftermarket family candidate; not Tesla OEM. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Tesla-compatible **aftermarket** TPMS family candidate](https://www.bandrewk.net/reverse-engineering-aftermarket-ble-tire-pressure-sensors-for-tesla-with-python/) | Name `tsTPMS` plus company field 555 (Tesla-assigned) and service UUID `1122`; variable-length `01,FF,02,09` structures | **18 / 11 / 33** | Independent hands-on study documents the same triple for **aftermarket** sensors. One address has 8 raw variants; neither Tesla OEM status, maker, model 3/Y fit nor pressure changes are established. |
