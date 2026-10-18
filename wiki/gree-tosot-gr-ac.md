---
qmd:
  metadata:
    title: "GREE / TOSOT GR-AC_* appliances"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# GREE / TOSOT `GR-AC_*` appliances

## How to recognize it

- **Local name:** `GR-AC_*`
- **Company field:** company field 3363, company field 93
- **AD shape (ordered type/data length):** `01/1,FF/17,09/22`
- **What it is:** GREE home-appliance ecosystem candidate.
- **Confidence and limits:** Ecosystem candidate; not every row is an air conditioner. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [GREE / TOSOT smart-appliance setup family](https://tosotdirect.com/pages/connect-app) | `GR-AC_*` (suffix hidden), `01/1,FF/17,09/22`; company field 3363 on 23 variants (23 / 23 / 186) and Realtek 93 on 9 (9 / 9 / 12) | **32 / 32 / 198** | Official guide mentions `GR-AC` **Wi-Fi hotspot** names and Bluetooth-assisted setup, not a BLE naming specification. GREE home-appliance ecosystem candidate; cannot assert every row is an air conditioner or TOSOT-branded. |
