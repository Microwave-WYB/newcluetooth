---
qmd:
  metadata:
    title: "Polestar Polestar* vehicle names"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Polestar `Polestar*` vehicle names

## How to recognize it

- **Local name:** `Polestar2`, `Polestar 3`, `Polestar*`
- **Company field:** company field 1534
- **Service / service-data UUID:** `FDFD`
- **What it is:** Polestar electric-vehicle name tokens.
- **Confidence and limits:** Model-name candidates; the protocol implemented is not established. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Polestar electric vehicles

The `Polestar*` cohort is **14 / 14 / 495**. Explicit model-name tokens support two model candidates; the shorter brand token is left unresolved. [Polestar 2 Bluetooth pairing](https://www.polestar.com/us/manual/polestar-2/2024/article/bd436382fbed6372c0a8015105dbf15d/) and [Polestar 3 digital-key information](https://www.polestar.com/us/support/faq/polestar-3-digital-key/) show product capabilities, **not** which protocol these advertisements implement.

| Sanitized name token | Variants / addresses / scans | Limit |
|---|---:|---|
| `Polestar2` | **8 / 8 / 451** | Eight varying 128-bit service UUIDs; no fixed model UUID. |
| `Polestar 3` | **4 / 4 / 12** | No advertised service UUID or company field. |
| `Polestar*`, model hidden/unresolved | **2 / 2 / 32** | `FDFD` service UUID and company field 1534 are auxiliary, not a model identity. |

Neither group proves phone-key use, vehicle ownership or number of distinct cars.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
