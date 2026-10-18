---
qmd:
  metadata:
    title: "CodeReader* BLE barcode/QR scanner class"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `CodeReader*` BLE barcode/QR scanner class

## How to recognize it

- **Local name:** `CodeReader*`
- **Service / service-data UUID:** `FFF0`, `180D`
- **AD shape (ordered type/data length):** `01/1,FF/4,09/13,02/2`
- **What it is:** Generic BLE barcode/QR-scanner class candidate.
- **Confidence and limits:** Class candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| Generic BLE barcode/QR-scanner class candidate | `CodeReader*` (suffix hidden), common UUID `FFF0`; `01/1,FF/4,09/13,02/2`, sometimes repeated `02` and generic `180D` | **8 / 8 / 15** | The [original Code CR2700 manual, §5.11.3](https://cdn.automationdirect.com/static/manuals/code/d032263.pdf) documents BLE HID keyboard pairing under the **different** discovery name `Code CR2700`, not `CodeReader*`; no manufacturer maps this observed name/`FFF0` to CR2700. A [CodeCorp reader](https://codecorp.com/products/code-reader-2700/) illustrates the scanner category only. `180D` does not imply a heart-rate device. |
