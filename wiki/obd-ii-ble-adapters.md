---
qmd:
  metadata:
    title: "OBD-II BLE diagnostic adapters: OBDII, ios-vlink, VEEPEAK"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# OBD-II BLE diagnostic adapters: `OBDII`, `ios-vlink`, `VEEPEAK`

## How to recognize it

- **Local name:** `OBDII`, `ios-vlink`, `VEEPEAK`
- **Service / service-data UUID:** `FFF0`, `18F0`
- **AD shape (ordered type/data length):** `01/1,0A/1,02/2,09/9`, `01/1,03/2,09/7`, `01/1,09/7,03/2,0A/1`
- **What it is:** Generic ELM327-style, Vgate iCar and Veepeak OBD-II BLE adapter candidates.
- **Confidence and limits:** Adapter-class candidates; vehicle and exact brand not identified. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## Catalogue entries

| Candidate and product source | Sanitized distinguishing signals | Variants / addresses / scans | Attribution limit |
|---|---|---:|---|
| [Generic ELM327-style OBD-II BLE diagnostic adapter](https://s-celles.github.io/canopen-studio/obd/) | Name exactly `OBDII` with common service UUID `FFF0`; four variants have no company field | **6 / 5 / 323** | Third-party protocol note supports a **multi-vendor adapter class**, not any particular brand/chip/model or the vehicle it was plugged into. |
| [Vgate iCar Pro BLE / compatible OBD-II diagnostic adapter candidate](https://vgatemall.com/products-detail/i-9/) | Exact case-insensitive `ios-vlink` and service UUID `18F0`; `01/1,0A/1,02/2,09/9` | **12 / 12 / 442** | Original iOS pairing instructions name the **hardware adapter** `IOS-Vlink`; not an iOS app counted as a device. Compatible clones, hardware revision and car identity remain unverified. |
| [Veepeak OBDCheck BLE / BLE+ OBD-II diagnostic adapter family candidate](https://veepeak.com/pages/obdcheck-ble-user-instructions) | Name exactly `VEEPEAK`, generic service UUID `FFF0`; `01/1,03/2,09/7` or `01/1,09/7,03/2,0A/1` | **5 / 5 / 27** | [Original BLE+ iOS instructions](https://veepeak.com/pages/instructions-for-using-car-scanner-with-obdcheck-bleplus-on-ios) confirm the **same** Bluetooth name for both products. Two AD layouts do not distinguish BLE vs BLE+; no connection, vehicle fault or diagnostic session inferred. |
