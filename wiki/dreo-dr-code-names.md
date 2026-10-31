---
qmd:
  metadata:
    title: "Dreo DR-H… model-code names"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Dreo `DR-H…` model-code names

## How to recognize it

- **Local name:** `Dreo*  (DR-HAF003S, DR-HTF001S, DR-HTF002S, DR-HTF007S)`
- **AD shape (ordered type/data length):** `01/1,09/21,08/21`
- **What it is:** Dreo pedestal/tower fans advertising full public model codes, ordered differently from the `5348` Dreo shape.
- **Confidence and limits:** Model-code candidates within existing fan categories. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Dreo fan model-name aliases

Another **6 exact `(addr,raw)` variants / 6 addresses / 51 scans** advertise a `Dreo*` name with complete public model-code tokens (all device-specific suffixes withheld), ordered `01/1,09/21,08/21` and no company/service UUID. Original product sources place these **within the existing pedestal-fan (PF) or tower-fan (TF) categories**, not four new appliance families:

| Advertised public code / original manufacturer product line | Variants / addresses / scans |
|---|---:|
| [`DR-HAF003S` → TurboPoly 513S pedestal/air-circulation fan](https://support.dreo.com/en/support/product/pf513s-613s/smart) | **2 / 2 / 13** |
| [`DR-HTF001S` → tower fan](https://www.dreo.com/pages/product-security-policy) | **1 / 1 / 1** |
| [`DR-HTF002S` → smart tower fan](https://www.dreo.com/pages/product-security-policy) | **2 / 2 / 34** |
| [`DR-HTF007S` → Tower Fan 307S](https://ca.dreo.com/products/tower-fan-307s) | **1 / 1 / 3** |

These full-name code observations are not included in the separate `DreoPF*`/`DreoTF*` **prefix** row counts, but are the **same product categories**, not extra product-family discoveries. The advertisement shape alone does not authenticate any model or feature.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
