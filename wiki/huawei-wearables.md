---
qmd:
  metadata:
    title: "HUAWEI HUAWEI* watches and bands"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# HUAWEI `HUAWEI*` watches and bands

## How to recognize it

- **Local name:** `HUAWEI*`
- **Company field:** company field 637
- **Service / service-data UUID:** `FDEE`, `3802`
- **What it is:** Huawei WATCH GT 3/GT 4 and Band 9/10 name tokens.
- **Confidence and limits:** Model-name candidates; `FDEE`/`3802` appear as service data. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

## HUAWEI watches and fitness bands

The case-insensitive `HUAWEI*` cohort is **5 exact `(addr,raw)` variants / 5 addresses / 63 scans** (exact mixed-case `Huawei*` finds none). Complete public model-name tokens plus [original WATCH GT 3/GT 4 comparison](https://consumer.huawei.com/cn/support/content/zh-cn15972101/), [Band 9/10 support](https://consumer.huawei.com/mx/support/content/es-us15994489/) and [Band 10 product page](https://consumer.huawei.com/en/wearables/band10/) support four wearable model candidates:

| Advertised public model token | Variants / addresses / scans | Service-**data** UUID |
|---|---:|---|
| `HUAWEI WATCH GT 3` | **2 / 2 / 59** | `FDEE` |
| `HUAWEI WATCH GT 4` | **1 / 1 / 1** | `FDEE` |
| `HUAWEI Band 9` | **1 / 1 / 1** | `3802` |
| `HUAWEI Band 10` | **1 / 1 / 2** | `3802` |

The private name suffixes stay hidden; none advertises a service UUID list (the table is **service data**). Company field 637 appears in all but GT 4; neither name nor auxiliary fields authenticate hardware, sizes or revisions. No phone, earbud or router model was identified in this cohort.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
