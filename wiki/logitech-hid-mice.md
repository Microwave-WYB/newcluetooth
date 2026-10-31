---
qmd:
  metadata:
    title: "Logitech M720 Triathlon / MX Master 2S HID mice"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Logitech `M720 Triathlon` / `MX Master 2S` HID mice

## How to recognize it

- **Local name:** `M720 Triathlon`, `MX Master 2S`
- **Service / service-data UUID:** `1812`
- **AD shape (ordered type/data length):** `01/1,19/2,03/2,09/14,0A/1`, `01/1,19/2,03/2,09/12,0A/1`
- **What it is:** Logitech wireless-mouse name tokens with HID service and mouse appearance.
- **Confidence and limits:** Model-name candidates; HID/mouse appearance 962 are generic. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Logitech M720 Triathlon wireless mouse

One exact `(addr,raw)` variant / one address / **2 scans** advertises the complete public product name `M720 Triathlon`, generic HID service UUID `1812` and Mouse Appearance 962 (`0x03C2`), with ordered `01/1,19/2,03/2,09/14,0A/1`. [Original Logitech M720 Triathlon product](https://www.logitech.com/en-us/shop/p/m720-triathlon) supports a multi-device wireless-mouse model candidate; no company field authenticates the brand. These BLE advertisements do not establish Unifying receiver use, host connection, active use or a verified physical-unit count.

A separate **1 exact variant / 1 address / 2 scans** advertises full public token [`MX Master 2S`](https://support.logi.com/hc/en-in/articles/360023459853-MX-MASTER-2S-WIRELESS-MOUSE), another Logitech wireless-mouse model candidate, with `1812` HID service, Mouse Appearance 962, ordered `01/1,19/2,03/2,09/12,0A/1`, and no company field. HID/mouse are **generic**, not Logitech authentication or evidence this is an M720. No Unifying use, host connection, authenticity or physical-unit count is established.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
