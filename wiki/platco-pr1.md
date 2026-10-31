---
qmd:
  metadata:
    title: "Platco PR1 BLE HID remote"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `Platco PR1` BLE HID remote

## How to recognize it

- **Local name:** `Platco PR1`
- **Company field:** company field 1767
- **Service / service-data UUID:** `1812`, `180F`, `180A`, `1802`
- **AD shape (ordered type/data length):** `01/1,02/8,09/10,FF/13,0A/1,12/4,19/2`
- **What it is:** Generic BLE HID remote candidate.
- **Confidence and limits:** Candidate; maker unconfirmed. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Generic BLE HID remote `Platco PR1` **candidate**](https://fccid.io/MG3-R34010/User-Manual/Users-Manual-5143489) | Case-insensitive exact public name `Platco PR1`; common HID `1812`, battery `180F`, device info `180A`, immediate alert `1802`; `01/1,02/8,09/10,FF/13,0A/1,12/4,19/2` | **6 / 6 / 13** | Third-party FCC mirror hosts a potentially relevant TV remote manual, **not** a direct PR1→R34010 mapping. Auxiliary company field 1767 is assigned to VELUX, conflicting with a Comcast/Universal Electronics inference; maker/TV use/model remain unconfirmed. |
