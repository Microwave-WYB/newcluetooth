---
qmd:
  metadata:
    title: "Sony LE_ audio name tokens: SRS-XB, WH, WF and WI-C speakers, headphones and earbuds"
    date: "2026-10-16"
    tags: [iot, ble, fingerprints]
    confidence: medium
---

# Sony `LE_` audio name tokens: SRS-XB, WH, WF and WI-C speakers, headphones and earbuds

## How to recognize it

- **Local name:** `LE_SRS-XB*`, `LE_WH-*`, `WH-CH720N`, `LE_WF-*`, `WF-C510`, `LE_WI-C100`
- **Company field:** company field 301
- **Service / service-data UUID:** `FE2C`, `FE03`, `FD2A`, `FD82`
- **AD shape (ordered type/data length):** `FF/21,01/1,09/11`, `16/(10,11 or 15),0A/1,09/11`
- **What it is:** Sony portable speakers, wireless headphones and earbuds identified by an `LE_<model>` name token (plus a non-`LE_` WH-CH720N/WF-C510 variant).
- **Confidence and limits:** Model-name candidates; company field 301, `FD2A` and Fast Pair `FE2C` are auxiliary, not model identifiers. Identifiers restate values recorded in the entries below; shared or generic values are flagged there.

Read-only simi `cluetooth` aggregates researched **2026-10-16**. The names below support **product/model candidates**, not authenticated identities. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, not physical devices. Hide any device-specific suffixes; research date is not observation date. The product source for each group documents the model/use, **not necessarily the exact advertisement syntax**.

First-party simi `cluetooth` read-only aggregates, researched **2026-10-16**. The principal feature here is a **sanitized model token in the advertised name**, not a claim that a generic BLE shape uniquely identifies a model. Counts are exact `(addr,raw)` variants / distinct observed addresses / scans, **not confirmed physical devices**. Hide all device-specific suffixes; names can be spoofed, and a research date is not an observation date. No hospital association is required.

## Sony EXTRA BASS portable speakers

Five exact `LE_SRS-XB` model-name tokens appear in **25 variants / 25 addresses / 167 scans**. [Sony's SRS-XB43 specifications](https://www.sony.com/electronics/support/wireless-speakers/srs-xb43/specifications) and [XB41/XB31 series guide](https://www.sony.fr/electronics/support/articles/00200861) document Bluetooth portable speakers; the model token is the primary fingerprint. `LE_` in a name does **not** imply LE Audio support.

| Advertised name-model token | Variants / addresses / scans |
|---|---:|
| `LE_SRS-XB23` | **5 / 5 / 54** |
| `LE_SRS-XB31` | **1 / 1 / 18** |
| `LE_SRS-XB33` | **9 / 9 / 44** |
| `LE_SRS-XB41` | **2 / 2 / 23** |
| `LE_SRS-XB43` | **8 / 8 / 28** |

Sixteen variants have `FF/21, 01/1, 09/11` with auxiliary Sony company field 301; nine have `16/(10,11 or 15), 0A/1, 09/11` with `FE2C` service data. A model can appear in both layouts: neither shape alone identifies the model.

## Sony WH wireless headphones

The `LE_WH-*` cohort is **170 variants / 137 addresses / 10,820 scans**, rechecked after an earlier 164 / 131 / 10,662 snapshot. [Sony's wireless-headphone model directory](https://www.sony.com/electronics/support/headphones-wireless-headphones) and [Sound Connect compatibility list](https://www.sony.com/electronics/support/wireless-headphones-bluetooth-headphones/wh-ult900n/articles/00346846) support seven model-name candidates. One model can yield many `(addr,raw)` variants; `LE_` does not prove LE Audio or active playback.

| Advertised model-name token | Variants / addresses / scans |
|---|---:|
| `LE_WH-1000XM5` | **96 / 64 / 2,024** |
| `LE_WH-1000XM4` | **62 / 61 / 8,380** |
| `LE_WH-1000XM6` | **4 / 4 / 30** |
| `LE_WH-XB910N` | **4 / 4 / 153** |
| `LE_WH-1000XM3` | **2 / 2 / 228** |
| `LE_WH-H910N` | **1 / 1 / 1** |
| `LE_WH-XB900N` | **1 / 1 / 4** |
| [`WH-CH720N` (no `LE_` prefix)](https://www.sony.co.uk/headphones/products/wh-ch720n) | **27 / 27 / 589** |

The **separate literal `WH-*` cohort** consists of those **27 / 27 / 589** `WH-CH720N` records, supporting a Sony wireless noise-cancelling over-ear headphone model candidate. Sony's product-page **search index** supplied the model/category match; direct full-body retrieval returned 403 in this follow-up. A direct read-only deduplicated query across `LE_WH-*` and `WH-*` gives **197 exact variants / 164 distinct addresses / 11,409 scans**, covered by the eight public model tokens in this table. The `WH-*` counts are **not part of** the earlier `LE_WH-*` 170 / 137 / 10,820 parent; no identity or LE Audio mode is authenticated.

## Sony WF true-wireless earbuds

The literal `LE_WF-*` cohort currently has **25 variants / 25 addresses / 249 scans**, versus an earlier 15 / 15 / 210 snapshot. Sony's [XM4](https://helpguide.sony.net/mdr/wf1000xm4/v1/en/contents/TP1000240552.html) and [XM5](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781303.html) guides acknowledge `LE_WF-` connection names. Model-name tokens, not company/UUID alone, support these candidates:

| Sanitized name token / original model source | Variants / addresses / scans |
|---|---:|
| [`LE_WF-1000XM5`](https://helpguide.sony.net/mdr/2963/v1/en/contents/TP1000781303.html) | **11 / 11 / 137** |
| [`LE_WF-C510`](https://electronics.sony.com/audio/headphones/all-headphones/p/wfc510-b) | **7 / 7 / 21** |
| [`LE_WF-1000XM6`](https://electronics.sony.com/audio/headphones/truly-wireless-earbuds/p/wf1000xm6-b/) | **2 / 2 / 36** |
| [`LE_WF-1000XM4`](https://helpguide.sony.net/mdr/wf1000xm4/v1/en/contents/TP1000240552.html) | **2 / 2 / 37** |
| [`LE_WF-C500`](https://www.sony.com/electronics/support/wireless-headphones-bluetooth-headphones/wf-c500) | **2 / 2 / 15** |
| [`LE_WF-C710N`](https://electronics.sony.com/audio/headphones/all-headphones/p/wfc710n-w/) — anomalous parsed fields, lower-confidence lead | **1 / 1 / 3** |

Some have Sony auxiliary company field 301 / `FD2A`, others `FE03` or Fast Pair `FE2C`, and some have no manufacturer field. None of these is a mandatory model identifier. One address does not prove one earbud set, case or physical device.

A separate non-`LE_` `WF-C510` name cohort adds **5 exact variants / 5 addresses / 81 scans** (ordered AD `0A/1,16/8 or 15,09/11`, Fast Pair service-data UUID `FE2C`). Together with the **7 / 7 / 21** `LE_WF-C510` row above, the **same Sony WF-C510 model** has **12 name/raw variants / 12 observed addresses / 102 scans**, with no address overlap in this snapshot. This extends advertisement coverage, **not** the number of identified product models or verified earbud units; different/random addresses cannot prove different physical sets.

A separate bounded read-only check found that all **25 / 25 / 249** literal `LE_WF-*` variants are accounted for by the six rows above. Literal bare `WF-*` totals **8 / 8 / 87**: the five `WF-C510` variants above are the only additional complete public model match, and the other **3 / 3 / 6 remain unassigned**. The latter are not counted as a seventh Sony earbud model merely because of the `WF-*` prefix.

## Sony WI-C100 neckband-style wireless earphones

All **7 exact `(addr,raw)` variants / 7 addresses / 10 scans** in the `LE_WI-C*` cohort include complete public token `LE_WI-C100`, supporting the [original Sony WI-C100 product](https://electronics.sony.com/audio/headphones/all-headphones/p/wic100-w). Six variants (**6 / 6 / 8**) advertise generic Fast Pair service-**data** `FE2C` without company field; one (**1 / 1 / 2**) also advertises service UUID `FD82` and Sony-assigned company field 301. These are **one model**, not separate earphone models; WI-C100 has left/right ears connected by a neck cable, **not** a `WF-` true-wireless earbud. Fast Pair alone does not identify Sony, and advertisements do not prove authenticity, color, firmware, audio playback or physical-unit count.

No full MACs, raw advertisement payloads, serial-bearing names or precise locations are published.
