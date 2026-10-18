---
qmd:
  metadata:
    title: "HP Poly Poly* Bluetooth headsets and adapter"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# HP Poly `Poly*` Bluetooth headsets and adapter

## How to recognize it

- **Local name:** `Poly*`
- **Service / service-data UUID:** `FE2C`, `91617f9c-29cc-4951-8a9a-c16f73706363`
- **What it is:** HP Poly Voyager headsets/earbuds and BT700 adapter distinguished by model-name token.
- **Confidence and limits:** Model-name candidates; `FE2C` is not Poly-exclusive. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## HP Poly Bluetooth audio

The `Poly*` cohort is **20 / 20 / 306**. HP's linked sources document the hardware; model-name tokens, not shared Fast Pair `FE2C`, distinguish the rows.

| Sanitized product token / HP source | Variants / addresses / scans |
|---|---:|
| [Voyager Legend 50 headset](https://www.hp.com/us-en/poly/headsets/bluetooth-headsets-and-earbuds/voyager-legend-50-series.html) | **16 / 16 / 275** |
| [Voyager Legend 30 headset](https://www.hp.com/us-en/poly/headsets/bluetooth-headsets-and-earbuds.html) | **1 / 1 / 1** |
| [Voyager Free 60 wireless earbuds](https://kaas.hpcloud.hp.com/pdf-public/pdf_8578296_en-US-1.pdf) | **1 / 1 / 6** |
| [BT700 USB Bluetooth audio adapter](https://www.hp.com/us-en/shop/pdp/poly-bt700-usb-a-bluetooth-adapter) | **2 / 2 / 24** |

BT700 is **an adapter, not a headset**. `FE2C` is not Poly-exclusive; a subset of Legend 50 also advertises UUID `91617f9c-29cc-4951-8a9a-c16f73706363`.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
