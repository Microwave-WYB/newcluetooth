---
qmd:
  metadata:
    title: "Eight Sleep POD-*"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Eight Sleep `POD-*`

## How to recognize it

- **Local name:** `POD-*`
- **AD shape (ordered type/data length):** `07/16,09/11`
- **What it is:** Eight Sleep Pod smart-bed temperature-control family candidate.
- **Confidence and limits:** Family candidate; OUI clues are not authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Eight Sleep Pod smart-bed temperature-control family **candidate**](https://www.eightsleep.com/product/pod-cover/) | `POD-*` (device suffix hidden, name 11 bytes), shared withheld 128-bit service UUID and `07/16,09/11`; 5 address OUIs third-party assigned to Eight Sleep, 2 to module maker Ezurio | **7 / 7 / 12** | [Original Bluetooth backup-mode guide](https://help.eightsleep.com/en_us/app-says-pod-offline-rJrJuG8qE) confirms Pod BLE control but **not** this naming/UUID. OUI clues are not authentication, module OUI does not identify finished product; no Pod generation, active backup mode or bed count inferred. |
