---
qmd:
  metadata:
    title: "Trimble trimble* (company field 1021)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Trimble `trimble*` (company field 1021)

## How to recognize it

- **Local name:** `trimble*`
- **Company field:** company field 1021
- **AD shape (ordered type/data length):** `09/15–16,FF/10–14`
- **What it is:** Trimble-branded BLE equipment candidate; category unknown.
- **Confidence and limits:** Category unknown. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Trimble-branded BLE equipment candidate; category unknown](https://receiverhelp.trimble.com/alloy-gnss/en-us/bluetoothInfo.html) | Case-insensitive `trimble*` (suffix hidden), name length 15–16; Trimble-assigned company field 1021; ordered `09/15–16,FF/10–14`, no service UUID | **9 / 9 / 13** | Alloy GNSS docs describe Bluetooth names but **do not** establish these are GNSS receivers, construction tags or Alloy units. No exact model token identified. |
