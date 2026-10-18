---
qmd:
  metadata:
    title: "Samsung Galaxy Watch and Galaxy Ring (company field 117, FD69)"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Samsung Galaxy Watch and Galaxy Ring (company field 117, `FD69`)

## How to recognize it

- **Local name:** `Galaxy Watch*`, `Galaxy Ring`
- **Company field:** company field 117
- **Service / service-data UUID:** `FD69`
- **AD shape (ordered type/data length):** `01/1,03/2,16/22,09/20`, `01/1,FF/15,19/2,09/20`, `01/1,FF/20,09/19`
- **What it is:** Samsung Galaxy Watch4–8 and Galaxy Ring name tokens.
- **Confidence and limits:** Model-name candidates; not device authentication. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## Samsung Galaxy Watch7 smartwatches

All **16 exact `(addr,raw)` variants / 16 addresses / 5,626 scans** have public name token `Galaxy Watch7`, supporting **one** [original Samsung Galaxy Watch7 product family](https://www.samsung.com/us/watches/galaxy-watch7/buy/galaxy-watch7-40mm-cream-wifi-bluetooth-sku-sm-l300nzeaxaa/), not two models. **8 / 8 / 1,387** carry service and service-data UUID `FD69` with ordered `01/1,03/2,16/22,09/20`; the other **8 / 8 / 4,239** carry Samsung-assigned company field 117 with `01/1,FF/15,19/2,09/20` and no listed service UUID. The [44 mm manufacturer example](https://www.samsung.com/us/watches/galaxy-watch7/buy/galaxy-watch7-44mm-silver-wifi-bluetooth-sku-sm-l310nzsaxaa/) establishes available variants, **not** which are present here. Neither advertising form nor `FD69` alone identifies watch size, radio option, order code, authenticity, ownership, lost/offline state or physical-watch count.

## Samsung Galaxy Watch6 and Watch6 Classic smartwatches

A separate **18 exact `(addr,raw)` variants / 18 addresses / 226 scans** with complete public `Galaxy Watch6` token split into **two name-confirmed families**, not four advertising models: [`Galaxy Watch6`](https://news.samsung.com/us/samsung-galaxy-watch6-series-classic-inspiring-best-self-day-night-unpacked-2023/) without `Classic` (**12 / 12 / 60**) and [`Galaxy Watch6 Classic`](https://www.samsung.com/mm/watches/galaxy-watch/galaxy-watch6-classic-43mm-silver-bluetooth-sm-r950nzsaasa/) (**6 / 6 / 166**). Within Watch6, six variants carry `FD69` service/service-data UUID (**6 / 6 / 15**) and six Samsung-assigned company field 117 (**6 / 6 / 45**); all Classic variants carry company 117, with two auxiliary-data lengths (**3 / 3 / 3** and **3 / 3 / 163**). The original Samsung series announcement distinguishes Classic's rotating bezel, but no radio size, LTE variant, color, order SKU, firmware or authentic physical-watch count is inferred. Shared `FD69`/company-field shapes with Watch7 do **not** merge generations.

## Four other Samsung Galaxy Watch public model tokens

The following complete public name tokens are separate from the Watch6, Watch6 Classic and Watch7 cohorts above. `Classic` is matched before the unsuffixed Watch4 token; neither a name prefix nor any linked product-page retail SKU authenticates an observed device.

| Public model-name token / original Samsung page | Exact `(addr,raw)` variants / addresses / scans |
|---|---:|
| [`Galaxy Watch4` (non-Classic)](https://www.samsung.com/ca/support/model.SM-R860NZSAXAC/) | **63 / 63 / 16,504** |
| [`Galaxy Watch5` (non-Pro)](https://www.samsung.com/latin/watches/galaxy-watch/galaxy-watch5-44mm-sapphire-bluetooth-sm-r910nzbalta/) | **38 / 38 / 11,041** |
| [`Galaxy Watch4 Classic`](https://www.samsung.com/ae/watches/galaxy-watch/galaxy-watch4-classic-black-bluetooth-sm-r890nzkamea/buy/) | **17 / 17 / 132** |
| [`Galaxy Watch8` (non-Classic)](https://www.samsung.com/us/watches/galaxy-watch8/) | **6 / 6 / 11** |

Linked sizes, colors, connectivity options and regional SKUs are examples of original model pages, **not observed configurations**. These four candidates do not establish completeness of the wider `Galaxy Watch*` name cohort, physical watch count or device authenticity.

## Samsung Galaxy Ring smart ring

One exact `(addr,raw)` variant / one address / **3 scans** has public token `Galaxy Ring`, Samsung-assigned company field 117 and ordered `01/1,FF/20,09/19`, without service or service-data UUID. [Original Samsung Galaxy Ring support](https://www.samsung.com/us/support/answer/ANS10003278/) establishes the sleep/activity-monitoring smart-ring family, **distinct from the Galaxy Watch cohort**; advertising does not authenticate an individual ring or reveal size, color, usage or deployment setting.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
