---
qmd:
  metadata:
    title: "PayRange PayRange BluKey"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# PayRange `PayRange` BluKey

## How to recognize it

- **Local name:** `PayRange`
- **Service / service-data UUID:** `da2b84f1-6279-48de-bdc0-afbea0226079`
- **AD shape (ordered type/data length):** `01/1,07/16,09/8,FF/15`
- **What it is:** PayRange machine-side payment hardware.
- **Confidence and limits:** Family candidate. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [PayRange BluKey machine-side payment-hardware family](https://payrange.com/products/blukey/) | Name exactly `PayRange`; service UUID `da2b84f1-6279-48de-bdc0-afbea0226079`; `01/1,07/16,09/8,FF/15` | **95 / 7 / 319** | [Phone app connects to machines over Bluetooth](https://payrange.com/consumers/payrange-app/); variants are **not** devices. Cannot determine BluKey Plus/Pulse/Pro or vending versus laundry machine. |
