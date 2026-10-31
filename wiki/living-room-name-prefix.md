---
qmd:
  metadata:
    title: "living* shared room-name prefix"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# `living*` shared room-name prefix

## How to recognize it

- **Local name:** `living*`
- **Company field:** company field 1480, company field 117
- **Service / service-data UUID:** `FE0F`, `FEA0`, `FE4F`, `252A`
- **AD shape (ordered type/data length):** `06/16,16/8,08/19`
- **What it is:** Not one product: a room-name prefix over several unrelated ecosystems.
- **Confidence and limits:** Family-level clues only; cannot be summed as one product. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

These are **product or family candidates**, not authenticated device identities. A fingerprint may combine a sanitized local name, advertised UUID, manufacturer field or ordered AD structure; **shape need not be unique**. Counts below are exact `(addr,raw)` advertisement variants / distinct observed addresses / summed scans from read-only queries of simi's first-party `cluetooth` database on **2026-10-16**. These are different from physical-device counts. Research date is not observation date. No hospital relationship is required or inferred. AD lengths below count **data bytes only**, excluding the length and type bytes; types and lengths preserve their observed order.

## `living*` is a shared room-name prefix, not one product

Case-insensitive `living*` currently spans **13 exact `(addr,raw)` variants / 13 addresses / 129 scans**, versus an earlier 10 / 10 / 102 snapshot. The public room word and any manufacturer-assigned code below are only **family-level clues**; device-specific name suffixes stay hidden.

| Distinct advertising clue / product-category source | Variants / addresses / scans | Limit |
|---|---:|---|
| Signify-assigned service/service-data `FE0F`; [Philips Hue original Bluetooth setup](https://www.philips-hue.com/en-us/explore-hue/faq/controls/how-to-connect-philips-hue-without-a-bridge) | **4 / 4 / 14** | Lighting-ecosystem candidate, not a particular bulb/bridge. |
| Google-assigned service/service-data `FEA0`; [Google original home device guide](https://support.google.com/googlehome/answer/7381505?hl=en) | **4 / 4 / 85** | Google-related service, not an identified speaker/display. |
| Somfy-assigned company field 1480; [original TaHoma controls](https://www.somfysystems.com/en-us/products/controls/tahoma/tahoma-by-somfy-app) | **2 / 2 / 2** | Home-control/shade ecosystem candidate, not TaHoma SKU. |
| Molekule-assigned service `FE4F`; [original Air Bluetooth pairing](https://help.molekule.com/hc/en-us/articles/115006094428-How-to-pair-and-connect-Molekule-Air-to-Bluetooth-and-WiFi) | **1 / 1 / 25** | Purifier-ecosystem candidate, not a confirmed Air model. |
| Samsung-assigned company field 117, no service UUID | **1 / 1 / 2** | Product category unknown, not a confirmed TV. |
| No company field, service-data `252A` and withheld 128-bit UUID; `06/16,16/8,08/19` | **1 / 1 / 1** | Completely unassigned; only exact uppercase `LIVING*` hit. |

These groups cannot be summed as one brand, product model or count of physical devices. The observed prefix is consistent with a room name but customization is not established.
